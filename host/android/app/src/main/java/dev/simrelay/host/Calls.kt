// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import dev.simrelay.protocol.v1.CallAction
import dev.simrelay.protocol.v1.CallEvent
import dev.simrelay.protocol.v1.Dial
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges Telecom calls (seen by CallMonitor, our non-UI InCallService) to protocol CallEvents, and
 * carries out the client's CallAction / Dial. Also drives the audio daemon: only an ACTIVE (or DIALING) call has
 * its routes and mutes applied, and only with full voice on or for the guided call test (VoiceGate).
 */
object CallRegistry {
    private const val TAG = "SimRelayHost"
    private val ids = ConcurrentHashMap<Call, String>()
    private val byId = ConcurrentHashMap<String, Call>()
    private val clientUuids = ConcurrentHashMap<String, String>()   // call id -> client's CallKit UUID
    private val lastSent = ConcurrentHashMap<String, CallEvent.State>() // send state changes only
    @Volatile private var pendingDial: Pair<String, Long>? = null     // client uuid, when requested
    private lateinit var app: Context

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun added(call: Call) {
        val id = UUID.randomUUID().toString()
        ids[call] = id
        byId[id] = call
        val pd = pendingDial
        if (call.details.callDirection == Call.Details.DIRECTION_OUTGOING && pd != null &&
            System.currentTimeMillis() - pd.second < 15_000) {
            clientUuids[id] = pd.first
            pendingDial = null
        }
        changed(call)
    }

    fun changed(call: Call) {
        val e = event(call)
        if (lastSent.put(e.callId, e.state) != e.state) Relay.callEvent(e)
        // Audio runs while a call is ACTIVE, and for outgoing calls already while DIALING: the network's early
        // media (ringback, "number unavailable" announcements) then reaches the iPhone instead of the host.
        val audible = byId.values.firstOrNull { it.stateCompat == Call.STATE_ACTIVE }
            ?: byId.values.firstOrNull { it.stateCompat == Call.STATE_DIALING }
        if (audible != null && VoiceGate.allows(ids[audible]?.let { clientUuids[it] }))
            Daemon.callActive(maxOf(slotOf(audible), 0) + 1)   // slot unknown -> session 1
        else Daemon.callIdle()
    }

    fun removed(call: Call) {
        val id = ids.remove(call) ?: return
        if (lastSent.remove(id) != CallEvent.State.ENDED) Relay.callEvent(event(call, forceEnded = true, id = id))
        byId.remove(id)
        clientUuids.remove(id)
        if (byId.values.none { it.stateCompat == Call.STATE_ACTIVE || it.stateCompat == Call.STATE_DIALING }) Daemon.callIdle()
    }

    /** Current calls, for replay after the client reconnects. */
    fun snapshot(): List<CallEvent> = byId.values.map { event(it) }

    fun action(a: CallAction): String? {
        val call = byId[a.callId] ?: return "no such call ${a.callId}"
        Log.i(TAG, "client action ${a.action} on ${a.callId}")
        when (a.action) {
            CallAction.Action.ANSWER -> call.answer(VideoProfile.STATE_AUDIO_ONLY)
            CallAction.Action.REJECT -> call.reject(false, null)
            CallAction.Action.HANG_UP -> call.disconnect()
            CallAction.Action.HOLD -> call.hold()
            CallAction.Action.UNHOLD -> call.unhold()
            CallAction.Action.DTMF -> a.dtmf.firstOrNull()?.let { call.playDtmfTone(it); call.stopDtmfTone() }
            else -> return "bad action ${a.action}"
        }
        return null
    }

    @SuppressLint("MissingPermission") // CALL_PHONE / READ_PHONE_STATE granted by the Magisk module
    fun dial(d: Dial): String? {
        if (d.number.isBlank()) return "empty number"
        val pd = pendingDial
        if ((pd != null && System.currentTimeMillis() - pd.second < 15_000) ||
            byId.values.any { it.stateCompat in setOf(Call.STATE_DIALING, Call.STATE_CONNECTING) })
            return "a call is already being dialled"
        val tm = app.getSystemService(TelecomManager::class.java)
        val extras = Bundle()
        val account = accountForSlot(d.slot)
        if (d.slot >= 0 && account == null) return "SIM ${d.slot + 1} is switched off"
        account?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        pendingDial = d.clientCallUuid to System.currentTimeMillis()
        if (d.voiceTest) VoiceGate.testUuid = d.clientCallUuid
        Log.i(TAG, "client dial slot=${d.slot}${if (d.voiceTest) " (call test)" else ""}")
        tm.placeCall(Uri.fromParts("tel", d.number, null), extras)
        return null
    }

    // ------------------------------------------------------------------ mapping

    private fun event(call: Call, forceEnded: Boolean = false, id: String? = null): CallEvent {
        val d = call.details
        val raw = d.handle?.schemeSpecificPart.orEmpty()
        val slot = slotOf(call)
        val withheld = d.handlePresentation != TelecomManager.PRESENTATION_ALLOWED || raw.isEmpty()
        val state = if (forceEnded) CallEvent.State.ENDED else when (call.stateCompat) {
            Call.STATE_RINGING -> CallEvent.State.RINGING
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT,
            Call.STATE_PULLING_CALL -> CallEvent.State.DIALING
            Call.STATE_ACTIVE -> CallEvent.State.ACTIVE
            Call.STATE_HOLDING -> CallEvent.State.HOLDING
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> CallEvent.State.ENDED
            else -> CallEvent.State.DIALING
        }
        val callId = id ?: ids[call].orEmpty()
        return CallEvent.newBuilder()
            .setCallId(callId)
            .setState(state)
            .setIncoming(d.callDirection == Call.Details.DIRECTION_INCOMING)
            .setNumberRaw(if (withheld) "" else raw)
            .setNumberE164(if (withheld) "" else e164(raw, slot))
            .setNumberWithheld(withheld)
            .setSlot(slot)
            .setStateSinceMs(System.currentTimeMillis())
            .setCause(if (state == CallEvent.State.ENDED) cause(d.disconnectCause) else CallEvent.DisconnectCause.CAUSE_UNSPECIFIED)
            .setClientCallUuid(clientUuids[callId].orEmpty())
            .build()
    }

    private fun cause(dc: DisconnectCause?): CallEvent.DisconnectCause = when (dc?.code) {
        DisconnectCause.LOCAL -> CallEvent.DisconnectCause.LOCAL
        DisconnectCause.REMOTE -> CallEvent.DisconnectCause.REMOTE
        DisconnectCause.REJECTED -> CallEvent.DisconnectCause.REJECTED
        DisconnectCause.MISSED -> CallEvent.DisconnectCause.MISSED
        DisconnectCause.BUSY -> CallEvent.DisconnectCause.BUSY
        DisconnectCause.ERROR -> CallEvent.DisconnectCause.ERROR
        else -> CallEvent.DisconnectCause.CAUSE_UNSPECIFIED
    }

    /** 0-based SIM slot of the call's account; -1 if unknown. */
    fun slotOf(call: Call): Int = runCatching {
        val subId = app.getSystemService(TelephonyManager::class.java).getSubscriptionId(call.details.accountHandle)
        SubscriptionManager.getSlotIndex(subId)
    }.getOrDefault(-1)

    private fun e164(raw: String, slot: Int): String {
        if (raw.isEmpty()) return ""
        val iso = Sims.countryIso(app, slot).ifEmpty { "tr" }
        return PhoneNumberUtils.formatNumberToE164(raw, iso.uppercase()) ?: ""
    }

    @SuppressLint("MissingPermission")
    private fun accountForSlot(slot: Int): PhoneAccountHandle? {
        if (slot < 0) return null
        val telecom = app.getSystemService(TelecomManager::class.java)
        val tm = app.getSystemService(TelephonyManager::class.java)
        return telecom.callCapablePhoneAccounts.firstOrNull {
            SubscriptionManager.getSlotIndex(tm.getSubscriptionId(it)) == slot
        }
    }
}

/** Call.Details#getState is API 31; Call#getState (deprecated) gives the same on Android 11. */
@Suppress("DEPRECATION")
val Call.stateCompat: Int get() = if (android.os.Build.VERSION.SDK_INT >= 31) details.state else state
