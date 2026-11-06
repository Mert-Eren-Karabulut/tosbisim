// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
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
    private val lastSent = ConcurrentHashMap<String, CallEvent>()       // send changes only
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
            Log.i(TAG, "call $id: the phone's, from before a host restart")
        }
        changed(call)
    }

    fun changed(call: Call) {
        send(call)
        if (!dialingPhase()) RootCallState.stop()
        // Audio runs while a call is ACTIVE, and for outgoing calls already while DIALING: the network's early
        // media (ringback, "number unavailable" announcements) then reaches the iPhone instead of the host.
        // The iPhone's call first: a call taken on the host can be active while the iPhone's own waits on hold.
        val active = byId.values.filter { it.stateCompat == Call.STATE_ACTIVE }
        val dialing = byId.values.filter { it.stateCompat == Call.STATE_DIALING }
        val audible = active.firstOrNull(::isOwned) ?: active.firstOrNull() ?: dialing.firstOrNull(::isOwned) ?: dialing.firstOrNull()
        val relayed = audible != null && isOwned(audible) && VoiceGate.allows(ids[audible]?.let { clientUuids[it] })
        HotspotPause.calls(relayed, live = liveCalls())
        if (relayed && audible != null) {
            BasicSilence.set(app, false)
            Daemon.callActive(Daemon.profile()?.sessionForSlot(maxOf(slotOf(audible), 0)) ?: (maxOf(slotOf(audible), 0) + 1),
                connected = audible.stateCompat == Call.STATE_ACTIVE)
        } else {
            Daemon.callIdle()
            // A call without relayed audio (Basic mode, or one the iPhone didn't dial or answer): the host stays quiet.
            // TosbiSim governs every call from install until "Stop using this phone as a host", box mode or not, as
            // the phone app ([BasicSilence.wanted]: another phone app's call is its owner's).
            BasicSilence.set(app, audible != null && BasicSilence.wanted(isOwned(audible), Onboarding.dialerRole(app)))
        }
    }

    fun removed(call: Call) {
        val id = ids.remove(call) ?: return
        if (lastSent.remove(id)?.state != CallEvent.State.ENDED) Relay.callEvent(event(call, forceEnded = true, id = id))
        byId.remove(id)
        clientUuids.remove(id)
        alertingIds.remove(id)
        if (!dialingPhase()) RootCallState.stop()
        disown(call, id)
        if (byId.values.none { it.stateCompat == Call.STATE_ACTIVE || it.stateCompat == Call.STATE_DIALING }) Daemon.callIdle()
        HotspotPause.calls(relayed = false, live = liveCalls())     // a call that's still relayed says so at its next change
    }

    /** Any call not yet over (a held one counts: the hotspot stays paused through a hold). */
    private fun liveCalls() = byId.values.any { it.stateCompat != Call.STATE_DISCONNECTED }

    private fun send(call: Call) {
        val e = event(call)
        val prev = lastSent.put(e.callId, e)
        if (prev == null || prev.state != e.state || prev.alerting != e.alerting || prev.alertingKnown != e.alertingKnown) Relay.callEvent(e)
    }

    // ------------------------------------------------------------------ ringback
    // The iPhone plays no ringback until the far end rings (CallEvent.alerting), like any phone. Two sources: the
    // modem's precise call state (RootCallState, any phone) and Samsung's own connection event. The client relies on
    // it (alerting_known) only on a firmware where an alert was seen before, and not after this dial's watcher failed;
    // otherwise it rings back from the start of dialing, as before 0.12.14.
    private val alertingIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var watchLost = false
    private const val SAMSUNG_ALERTING = "com.samsung.telecom.Call.EVENT_SET_ALERTING"

    private val dialingStates = setOf(Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT)
    /** An outgoing call is on its way (or the client's Dial is, before Telecom adds the call). */
    private fun dialingPhase() = byId.values.any { it.stateCompat in dialingStates } ||
        pendingDial?.let { System.currentTimeMillis() - it.second < 15_000 } == true

    private fun alertingKnown(call: Call): Boolean =
        call.details.callDirection == Call.Details.DIRECTION_OUTGOING && isOwned(call) && !watchLost &&
            prefs.getString("alerting_seen", null) == Build.FINGERPRINT

    /** Main thread: the far end of [call] rings. */
    private fun alerting(call: Call, how: String) {
        val id = ids[call] ?: return
        if (call.stateCompat !in dialingStates || !alertingIds.add(id)) return
        Log.i(TAG, "call ${id.take(8)} alerting ($how)")
        if (prefs.getString("alerting_seen", null) != Build.FINGERPRINT) prefs.edit().putString("alerting_seen", Build.FINGERPRINT).apply()
        send(call)
    }

    fun connectionEvent(call: Call, event: String) { if (event == SAMSUNG_ALERTING) alerting(call, "Samsung event") }

    private fun watchAlerting() {
        watchLost = false
        RootCallState.watch(app, onState = { slot, state ->
            if (state == RootCallState.ALERTING) byId.values.filter {
                it.details.callDirection == Call.Details.DIRECTION_OUTGOING && slotOf(it).let { s -> s == slot || s < 0 }
            }.forEach { alerting(it, "precise call state") }
        }, onLost = { why ->
            val dialing = byId.values.filter { it.stateCompat in dialingStates }
            if (dialing.any(::alertingKnown)) {
                Diag.event("alerting_watch_lost", org.json.JSONObject().put("what", why.take(60)), withLog = false)
            }
            watchLost = true                                // the client rings back itself from now on
            dialing.forEach(::send)
        })
    }

    /** Any call at all (ringing, dialling, active or held): cheap, and safe before [init]. */
    fun anyCall(): Boolean = byId.isNotEmpty()

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
        watchAlerting()
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
            .setAlerting(state == CallEvent.State.DIALING && callId in alertingIds)
            .setAlertingKnown(state == CallEvent.State.DIALING && alertingKnown(call))
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
 * bag or a drawer. Without a profile there are no device mutes to apply, so use what every Android phone has: mic mute
 * and the lowest call volume, restored when the call ends. (Hard rule 1: the host stays silent.)
 * The volume to give back is kept on disk: a host restarted mid-call (keep-alive, update, crash) found the minimum,
 * saved that, and made it the phone's call volume for good (audit, 20 Oct 2026).
 */
object BasicSilence {
    private const val TAG = "SimRelayHost"
    private const val KEY = "voice_volume"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("basic_silence", Context.MODE_PRIVATE)

    /**
     * Which calls without relayed audio are silenced: the client's always (it was answered or dialled from there, with
     * the host in a bag or a drawer), any other only while TosbiSim is the phone app. With another phone app holding
     * the role, a call the client didn't dial or answer was taken on this phone by a person holding it (the role given
     * back, or changed by hand): muting its mic cut its owner off (Mi 11 Pro, 4 Nov 2026).
     */
    fun wanted(clientsCall: Boolean, ourPhoneApp: Boolean) = clientsCall || ourPhoneApp

    fun set(ctx: Context, on: Boolean) {
        val am = ctx.getSystemService(android.media.AudioManager::class.java) ?: return
        val p = prefs(ctx)
        val saved = p.getInt(KEY, -1)
        runCatching {
            if (on && saved < 0) {
                p.edit().putInt(KEY, am.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)).commit()
                am.isMicrophoneMute = true
                am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, am.getStreamMinVolume(android.media.AudioManager.STREAM_VOICE_CALL), 0)
                Log.i(TAG, "basic-mode call: host mic muted, call volume at minimum")
            } else if (!on && saved >= 0) {
                am.isMicrophoneMute = false
                am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, saved, 0)
                p.edit().remove(KEY).apply()
                Log.i(TAG, "basic-mode call over: host mic and call volume restored")
            }
        }.onFailure { Log.w(TAG, "basic-mode silence: $it") }
    }
}
