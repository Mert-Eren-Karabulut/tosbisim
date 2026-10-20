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
 * its routes and mutes applied, only with full voice on or for the guided call test (VoiceGate), and only for a call
 * the iPhone owns: one it dialled or answered. Any other call is not relayed (20 Oct 2026: a test phone's own host,
 * restarted mid-call, relayed a call answered on its screen and fed the caller silence); the host is the phone app,
 * so it shows no call screen, and it stays silent through such a call.
 */
object CallRegistry {
    private const val TAG = "SimRelayHost"
    private val ids = ConcurrentHashMap<Call, String>()
    private val byId = ConcurrentHashMap<String, Call>()
    private val clientUuids = ConcurrentHashMap<String, String>()   // call id -> client's CallKit UUID
    private val lastSent = ConcurrentHashMap<String, CallEvent.State>() // send state changes only
    @Volatile private var pendingDial: Pair<String, Long>? = null     // client uuid, when requested
    private val owned: MutableSet<String> = ConcurrentHashMap.newKeySet()   // call ids the iPhone dialled or answered
    private lateinit var app: Context

    fun init(ctx: Context) { app = ctx.applicationContext }

    // Ownership outlives a host restart mid-call (keep-alive, update, crash): keyed by Telecom's creation time, which
    // the same call keeps, in a small prefs set pruned of anything older than a day.
    private val prefs get() = app.getSharedPreferences("calls", Context.MODE_PRIVATE)
    private fun key(call: Call) = "${call.details.creationTimeMillis}"
    private fun own(call: Call, id: String) {
        owned.add(id)
        val now = System.currentTimeMillis()
        val keep = prefs.getStringSet("owned", emptySet()).orEmpty().filter { now - (it.toLongOrNull() ?: 0) < 86_400_000L }
        prefs.edit().putStringSet("owned", (keep + key(call)).toSet()).apply()
    }
    /** The iPhone dialled or answered it (a conference: any of its calls). */
    private fun isOwned(call: Call): Boolean =
        ids[call] in owned || call.children.any { ids[it] in owned } || call.parent?.let { ids[it] in owned } == true

    private fun disown(call: Call, id: String) {
        owned.remove(id)
        prefs.edit().putStringSet("owned", prefs.getStringSet("owned", emptySet()).orEmpty() - key(call)).apply()
    }

    fun added(call: Call) {
        val id = UUID.randomUUID().toString()
        ids[call] = id
        byId[id] = call
        val pd = pendingDial
        if (call.details.callDirection == Call.Details.DIRECTION_OUTGOING && pd != null &&
            System.currentTimeMillis() - pd.second < 15_000) {
            clientUuids[id] = pd.first
            pendingDial = null
            own(call, id)
        } else if (key(call) in prefs.getStringSet("owned", emptySet()).orEmpty()) {
            owned.add(id)                                   // the iPhone's call, from before this process started
            Log.i(TAG, "call $id: the iPhone's, from before a host restart")
        }
        changed(call)
    }

    fun changed(call: Call) {
        val e = event(call)
        if (lastSent.put(e.callId, e.state) != e.state) Relay.callEvent(e)
        // Audio runs while a call is ACTIVE, and for outgoing calls already while DIALING: the network's early
        // media (ringback, "number unavailable" announcements) then reaches the iPhone instead of the host.
        // The iPhone's call first: a call taken on the host can be active while the iPhone's own waits on hold.
        val active = byId.values.filter { it.stateCompat == Call.STATE_ACTIVE }
        val dialing = byId.values.filter { it.stateCompat == Call.STATE_DIALING }
        val audible = active.firstOrNull(::isOwned) ?: active.firstOrNull() ?: dialing.firstOrNull(::isOwned) ?: dialing.firstOrNull()
        if (audible != null && isOwned(audible) && VoiceGate.allows(ids[audible]?.let { clientUuids[it] })) {
            BasicSilence.set(app, false)
            Daemon.callActive(Daemon.profile()?.sessionForSlot(maxOf(slotOf(audible), 0)) ?: (maxOf(slotOf(audible), 0) + 1))
        } else {
            Daemon.callIdle()
            // A call without relayed audio (Basic mode, or one the iPhone didn't dial or answer): the host stays quiet.
            // TosbiSim governs every call from install until "Stop using this phone as a host", box mode or not.
            BasicSilence.set(app, audible != null)
        }
    }

    fun removed(call: Call) {
        val id = ids.remove(call) ?: return
        if (lastSent.remove(id) != CallEvent.State.ENDED) Relay.callEvent(event(call, forceEnded = true, id = id))
        byId.remove(id)
        clientUuids.remove(id)
        disown(call, id)
        if (byId.values.none { it.stateCompat == Call.STATE_ACTIVE || it.stateCompat == Call.STATE_DIALING }) Daemon.callIdle()
    }

    /** Current calls, for replay after the client reconnects. */
    fun snapshot(): List<CallEvent> = byId.values.map { event(it) }

    fun action(a: CallAction): String? {
        val call = byId[a.callId] ?: return "no such call ${a.callId}"
        Log.i(TAG, "client action ${a.action} on ${a.callId}")
        when (a.action) {
            CallAction.Action.ANSWER -> { own(call, a.callId); call.answer(VideoProfile.STATE_AUDIO_ONLY) }
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

    private fun e164(raw: String, slot: Int): String = Sims.e164(app, raw, slot)

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

/**
 * Basic mode (no measured audio path yet): an answered call would otherwise use the host's own earpiece and mic, in a
 * drawer. Without a profile there are no device mutes to apply, so use what every Android phone has: mic mute and
 * the lowest call volume, restored when the call ends. (Hard rule 1: the host stays silent.)
 */
object BasicSilence {
    private const val TAG = "SimRelayHost"
    private var saved: Int? = null

    fun set(ctx: android.content.Context, on: Boolean) {
        val am = ctx.getSystemService(android.media.AudioManager::class.java) ?: return
        runCatching {
            if (on && saved == null) {
                saved = am.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)
                am.isMicrophoneMute = true
                am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, am.getStreamMinVolume(android.media.AudioManager.STREAM_VOICE_CALL), 0)
                android.util.Log.i(TAG, "basic-mode call: host mic muted, call volume at minimum")
            } else if (!on && saved != null) {
                am.isMicrophoneMute = false
                am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, saved!!, 0)
                saved = null
                android.util.Log.i(TAG, "basic-mode call over: host mic and call volume restored")
            }
        }.onFailure { android.util.Log.w(TAG, "basic-mode silence: $it") }
    }
}
