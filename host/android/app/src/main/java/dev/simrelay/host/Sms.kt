// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import dev.simrelay.protocol.v1.SmsReceived
import dev.simrelay.protocol.v1.SmsSend
import dev.simrelay.protocol.v1.SmsSendResult
import java.util.UUID

private const val TAG = "SimRelayHost"

/**
 * Store-and-forward queue: an SMS stays here until the client acks it (protocol SmsAck), and is
 * replayed on every reconnect. Small, so SharedPreferences holding serialized protos is enough.
 */
object SmsStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sms_pending", Context.MODE_PRIVATE)

    fun add(ctx: Context, m: SmsReceived) {
        prefs(ctx).edit().putString(m.id, android.util.Base64.encodeToString(m.toByteArray(), android.util.Base64.NO_WRAP)).apply()
    }

    fun ack(ctx: Context, id: String) { prefs(ctx).edit().remove(id).apply() }

    fun pending(ctx: Context): List<SmsReceived> = prefs(ctx).all.values.mapNotNull { v ->
        runCatching { SmsReceived.parseFrom(android.util.Base64.decode(v as String, android.util.Base64.NO_WRAP)) }.getOrNull()
    }.sortedBy { it.receivedMs }
}

/** Default-SMS-app delivery: write to the provider (we are the default app now), queue, relay. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val from = parts[0].displayOriginatingAddress.orEmpty()
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1)
        val slot = SubscriptionManager.getSlotIndex(subId)
        val ts = parts[0].timestampMillis

        ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, from); put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.DATE_SENT, ts)
            put(Telephony.Sms.READ, 0); put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        })
        val iso = Sims.countryIso(ctx, slot).ifEmpty { "tr" }.uppercase()
        val msg = SmsReceived.newBuilder()
            .setId(UUID.randomUUID().toString())
            .setFromRaw(from)
            .setFromE164(PhoneNumberUtils.formatNumberToE164(from, iso) ?: "")
            .setBody(body)
            .setReceivedMs(ts)
            .setSlot(slot)
            .build()
        SmsStore.add(ctx, msg)
        Log.i(TAG, "SMS received on slot $slot (${body.length} chars), queued ${msg.id}")
        Relay.smsReceived(msg)
    }
}

/** Required for the SMS role. MMS is not relayed yet (M4 scope: SMS). */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Log.i(TAG, "MMS WAP push received (not relayed yet)") }
}

/** Required for the SMS role ("send to" entry point); the host has no UI. */
class ComposeSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); finish() }
}

/** Required for the SMS role ("respond via message" from the incoming-call screen). */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { stopSelf(startId); return START_NOT_STICKY }
}

object SmsSender {
    private const val ACTION_SENT = "dev.simrelay.host.SMS_SENT"
    private const val ACTION_DELIVERED = "dev.simrelay.host.SMS_DELIVERED"
    /** Messages the network confirmed as delivered: a later "send failed" for them is wrong and dropped. */
    private val delivered = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    /** When each message went out (elapsedRealtime), to tell a timeout from a real refusal. */
    private val sentAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun send(ctx: Context, s: SmsSend) {
        if (s.to.isBlank() || s.body.isEmpty()) {
            Relay.smsSendResult(SmsSendResult.newBuilder().setClientMsgId(s.clientMsgId).setOk(false)
                .setError("empty number or message").build())
            return
        }
        if (s.slot >= 0 && Sims.active(ctx).none { it.simSlotIndex == s.slot }) {
            Log.w(TAG, "SMS send ${s.clientMsgId}: SIM slot ${s.slot} is off or missing")
            Relay.smsSendResult(SmsSendResult.newBuilder().setClientMsgId(s.clientMsgId).setOk(false)
                .setError("SIM ${s.slot + 1} is switched off").build())
            return
        }
        val subId = Sims.subIdForSlot(ctx, s.slot)
        @Suppress("DEPRECATION")
        val sm = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
                 else SmsManager.getSmsManagerForSubscriptionId(subId)
        val parts = sm.divideMessage(s.body)
        val sent = ArrayList<PendingIntent>()
        val delivery = ArrayList<PendingIntent>()
        parts.forEachIndexed { i, _ ->
            val intent = Intent(ACTION_SENT).setPackage(ctx.packageName)
                .putExtra("id", s.clientMsgId).putExtra("last", i == parts.size - 1)
            sent += PendingIntent.getBroadcast(ctx, (s.clientMsgId + i).hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            // Delivery reports: MediaTek's VoLTE SMS (Redmi + Vodafone TR, 19 Oct) reported "generic failure" two
            // minutes after the network had accepted and delivered the message; the report is the real answer.
            val d = Intent(ACTION_DELIVERED).setPackage(ctx.packageName)
                .putExtra("id", s.clientMsgId).putExtra("last", i == parts.size - 1)
            delivery += PendingIntent.getBroadcast(ctx, ("d" + s.clientMsgId + i).hashCode(), d,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)   // mutable: the system adds the report
        }
        sentAt[s.clientMsgId] = android.os.SystemClock.elapsedRealtime()
        if (sentAt.size > 50) sentAt.keys.firstOrNull()?.let { sentAt.remove(it) }
        Log.i(TAG, "SMS send ${s.clientMsgId}: ${parts.size} part(s) on sub $subId")
        sm.sendMultipartTextMessage(s.to, null, parts, sent, delivery)
        ctx.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, s.to); put(Telephony.Sms.BODY, s.body)
            put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        })
    }

    /** Delivery report for the last part: delivered -> "sent" (overrides an earlier reported failure). */
    class DeliveredReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getStringExtra("id") ?: return
            if (!intent.getBooleanExtra("last", false)) return
            val pdu = intent.getByteArrayExtra("pdu") ?: return
            val status = runCatching { android.telephony.SmsMessage.createFromPdu(pdu, intent.getStringExtra("format")).status }.getOrDefault(-1)
            Log.i(TAG, "SMS $id delivery report: status 0x${Integer.toHexString(status)}")
            if (status in 0 until 0x20 && delivered.add(id)) {          // TP-Status 0x00-0x1F: delivered
                while (delivered.size > 50) delivered.remove(delivered.first())
                Relay.smsSendResult(SmsSendResult.newBuilder().setClientMsgId(id).setOk(true).build())
            }
        }
    }

    /** Result of each part; we report the last part (or the first failure). */
    class SentReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getStringExtra("id") ?: return
            val ok = resultCode == Activity.RESULT_OK
            if (!ok && id in delivered) { Log.i(TAG, "SMS $id: send failure (result $resultCode) after delivery: ignored"); return }
            if (!ok || intent.getBooleanExtra("last", false)) {
                // A generic failure only after a long wait is a timeout, not a refusal: MediaTek's VoLTE SMS (Redmi +
                // Vodafone TR) returns it ~130 s after the network accepted and delivered the message. Real refusals
                // (no service, bad number) come back within seconds. The client shows "not confirmed", not "failed".
                val waited = android.os.SystemClock.elapsedRealtime() - (sentAt[id] ?: android.os.SystemClock.elapsedRealtime())
                val unconfirmed = !ok && resultCode == android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE && waited > 60_000
                Log.i(TAG, "SMS $id sent=$ok (result $resultCode after ${waited / 1000} s)${if (unconfirmed) ": not confirmed" else ""}")
                Relay.smsSendResult(SmsSendResult.newBuilder().setClientMsgId(id).setOk(ok)
                    .setError(if (ok) "" else if (unconfirmed) "unconfirmed: the network didn't confirm in time" else "result code $resultCode").build())
            }
        }
    }
}
