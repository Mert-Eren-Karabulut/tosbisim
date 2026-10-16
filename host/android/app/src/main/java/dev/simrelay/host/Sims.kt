// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import dev.simrelay.protocol.v1.SetSim
import dev.simrelay.protocol.v1.Sim
import dev.simrelay.protocol.v1.UnlockSim
import dev.simrelay.protocol.v1.UnlockSimResult
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * SIM slots: per-SIM status for the client and the per-SIM settings it can change (line on/off, data
 * roaming, which SIM carries mobile data). The setters are @SystemApi: reached by reflection (system APIs
 * are not hidden-API restricted) and allowed by MODIFY_PHONE_STATE, which the Magisk module grants us as a
 * privileged app. Calls and SMS pick their SIM per request (Dial.slot / SmsSend.slot), so the host's own
 * default call/SMS SIM does not matter.
 */
object Sims {
    private const val TAG = "SimRelayHost"
    private val attempts = ConcurrentHashMap<Int, Int>()    // slot -> PIN/PUK attempts left (cached, see lockOf)

    @SuppressLint("MissingPermission")
    fun active(ctx: Context): List<SubscriptionInfo> =
        ctx.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()

    /** Inserted physical SIMs, including ones switched off from the client (not in the active list). */
    @SuppressLint("MissingPermission")
    fun inserted(ctx: Context): List<SubscriptionInfo> {
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        @Suppress("UNCHECKED_CAST")
        val all = runCatching {
            SubscriptionManager::class.java.getMethod("getAvailableSubscriptionInfoList").invoke(sm) as List<SubscriptionInfo>?
        }.onFailure { Log.w(TAG, "getAvailableSubscriptionInfoList: $it") }.getOrNull() ?: active(ctx)
        return all.filter { it.simSlotIndex >= 0 && !it.isEmbedded }.sortedBy { it.simSlotIndex }
    }

    /** SIM inserted/removed/switched, or default data SIM moved. */
    fun watch(ctx: Context, changed: () -> Unit) {
        ctx.getSystemService(SubscriptionManager::class.java).addOnSubscriptionsChangedListener(
            ctx.mainExecutor, object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() = changed()
            })
    }

    fun log(ctx: Context) = Log.i(TAG, "SIMs: " + status(ctx).joinToString("; ") {
        "slot ${it.slot} ${it.operatorName}/${it.countryIso} on=${it.enabled} signal=${it.signalLevel} volte=${it.volte} " +
            "roaming=${it.roaming} dataRoaming=${it.dataRoaming} data=${it.defaultData} card=${it.carrierId}"
    }.ifEmpty { "none" })

    fun countryIso(ctx: Context, slot: Int): String =
        active(ctx).firstOrNull { it.simSlotIndex == slot }?.countryIso.orEmpty()

    fun subIdForSlot(ctx: Context, slot: Int): Int =
        active(ctx).firstOrNull { slot < 0 || it.simSlotIndex == slot }?.subscriptionId
            ?: SubscriptionManager.getDefaultSmsSubscriptionId()

    @SuppressLint("MissingPermission")
    fun status(ctx: Context): List<Sim> {
        val defaultData = SubscriptionManager.getDefaultDataSubscriptionId()
        val activeIds = active(ctx).map { it.subscriptionId }.toSet()
        val inserted = inserted(ctx)
        // A PIN-locked SIM may have no subscription yet: list it by slot so the client can unlock it.
        val locked = (0 until ctx.getSystemService(TelephonyManager::class.java).activeModemCount)
            .filter { slot -> inserted.none { it.simSlotIndex == slot } && lockOf(ctx, slot) != Sim.Lock.UNLOCKED }
            .map { slot ->
                val lock = lockOf(ctx, slot)
                Sim.newBuilder().setSlot(slot).setEnabled(false).setLock(lock).setAttemptsLeft(attemptsFor(ctx, slot, lock)).build()
            }
        return (inserted.map { info -> simFor(ctx, info, defaultData, activeIds) } + locked).sortedBy { it.slot }
    }

    @SuppressLint("MissingPermission")
    private fun simFor(ctx: Context, info: SubscriptionInfo, defaultData: Int, activeIds: Set<Int>): Sim {
        val sub = info.subscriptionId
        val on = sub in activeIds
        val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub)
        val b = Sim.newBuilder()
            .setSlot(info.simSlotIndex)
            .setOperatorName(info.carrierName?.toString().orEmpty())
            .setCountryIso(info.countryIso.orEmpty())
            .setEnabled(on)
            .setDefaultData(sub == defaultData)
            .setCarrierId(cardId(info))
        if (on) {
            b.setSignalLevel(runCatching { tm.signalStrength?.level ?: 0 }.getOrDefault(0))
                .setRoaming(runCatching { tm.isNetworkRoaming }.getOrDefault(false))
                .setDataRoaming(runCatching { tm.isDataRoamingEnabled }.getOrDefault(false))
                .setVolte(volte(ctx, sub))
                .setNumberE164(runCatching { number(ctx, info) }.getOrDefault(""))
        }
        val lock = lockOf(ctx, info.simSlotIndex)
        if (lock == Sim.Lock.UNLOCKED) attempts.remove(info.simSlotIndex)
        return b.setLock(lock).setAttemptsLeft(attemptsFor(ctx, info.simSlotIndex, lock)).build()
    }

    // ------------------------------------------------------------------ SIM PIN

    private fun lockOf(ctx: Context, slot: Int): Sim.Lock =
        when (ctx.getSystemService(TelephonyManager::class.java).getSimState(slot)) {
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> Sim.Lock.PIN_REQUIRED
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> Sim.Lock.PUK_REQUIRED
            TelephonyManager.SIM_STATE_PERM_DISABLED -> Sim.Lock.PERM_DISABLED
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> Sim.Lock.OTHER_LOCK
            else -> Sim.Lock.UNLOCKED
        }

    /** Attempts left; asked once per lock with an empty PIN/PUK, exactly as AOSP's keyguard does (no attempt used). */
    private fun attemptsFor(ctx: Context, slot: Int, lock: Sim.Lock): Int {
        if (lock != Sim.Lock.PIN_REQUIRED && lock != Sim.Lock.PUK_REQUIRED) return -1
        return attempts.getOrPut(slot) {
            val r = supply(ctx, slot, if (lock == Sim.Lock.PUK_REQUIRED) "" else null, "")
            r?.second ?: -1
        }
    }

    /** Client PIN (or PUK + new PIN). Runs the modem call off the main thread; never logs the codes. */
    fun unlock(ctx: Context, u: UnlockSim, done: (UnlockSimResult) -> Unit) = Thread {
        val r = supply(ctx, u.slot, u.puk.ifEmpty { null }, u.pin)
        val ok = r?.first == true
        if (ok) attempts.remove(u.slot) else r?.second?.let { attempts[u.slot] = it }
        Thread.sleep(300)
        val lock = lockOf(ctx, u.slot)
        Log.i(TAG, "SIM slot ${u.slot} ${if (u.puk.isEmpty()) "PIN" else "PUK"} entry: ${if (ok) "unlocked" else "refused"}, " +
            "attempts left ${r?.second ?: "?"}, now $lock")
        done(UnlockSimResult.newBuilder().setSlot(u.slot).setOk(ok).setAttemptsLeft(if (ok) -1 else r?.second ?: -1)
            .setLock(lock).build())
    }.start()

    /** supplyIccLockPin / supplyIccLockPuk (@SystemApi, MODIFY_PHONE_STATE) -> (success, attempts left). */
    @SuppressLint("MissingPermission")
    private fun supply(ctx: Context, slot: Int, puk: String?, pin: String): Pair<Boolean, Int>? = runCatching {
        val sub = inserted(ctx).firstOrNull { it.simSlotIndex == slot }?.subscriptionId ?: subIdOfSlot(slot) ?: return null
        val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub)
        val res = if (puk != null)
            TelephonyManager::class.java.getMethod("supplyIccLockPuk", String::class.java, String::class.java).invoke(tm, puk, pin)
        else
            TelephonyManager::class.java.getMethod("supplyIccLockPin", String::class.java).invoke(tm, pin)
        val type = res!!.javaClass.getMethod("getResult").invoke(res) as Int            // PinResult: 0 = success
        val left = res.javaClass.getMethod("getAttemptsRemaining").invoke(res) as Int
        (type == 0) to left
    }.onFailure { Log.w(TAG, "SIM slot $slot PIN call failed: ${(it.cause ?: it).javaClass.simpleName}") }.getOrNull()

    /** Turn the SIM PIN lock on/off (needs the current PIN; a wrong one uses an attempt). Debug/test path. */
    fun setLockEnabled(ctx: Context, slot: Int, pin: String, on: Boolean) = Thread {
        val r = runCatching {
            val sub = inserted(ctx).firstOrNull { it.simSlotIndex == slot }?.subscriptionId ?: error("no SIM in slot $slot")
            val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub)
            val res = TelephonyManager::class.java.getMethod("setIccLockEnabled", Boolean::class.java, String::class.java)
                .invoke(tm, on, pin)!!
            val type = res.javaClass.getMethod("getResult").invoke(res) as Int
            val left = res.javaClass.getMethod("getAttemptsRemaining").invoke(res) as Int
            "${if (type == 0) "OK" else "REFUSED (result $type)"}, attempts left $left"
        }.getOrElse { "failed: ${(it.cause ?: it)}" }
        Log.i(TAG, "SIM slot $slot PIN lock ${if (on) "on" else "off"}: $r")
    }.start()

    private fun subIdOfSlot(slot: Int): Int? = runCatching {
        (SubscriptionManager::class.java.getMethod("getSubscriptionIds", Int::class.java).invoke(null, slot) as IntArray?)
            ?.firstOrNull()
    }.getOrNull()

    /**
     * Applies a client SetSim. Returns null on success or a short reason. A SIM seen for the first time gets
     * data roaming switched off (cost protection) before the client ever asks.
     */
    fun apply(ctx: Context, s: SetSim): String? {
        val info = inserted(ctx).firstOrNull { it.simSlotIndex == s.slot } ?: return "no SIM in slot ${s.slot}"
        val sub = info.subscriptionId
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        if (s.hasEnabled()) {
            val ok = call { SubscriptionManager::class.java
                .getMethod("setUiccApplicationsEnabled", Int::class.java, Boolean::class.java).invoke(sm, sub, s.enabled) }
            if (!ok) return "this phone cannot switch the SIM ${if (s.enabled) "on" else "off"}"
            Log.i(TAG, "SIM slot ${s.slot} switched ${if (s.enabled) "on" else "off"}")
        }
        if (s.hasDataRoaming()) {
            if (!setDataRoaming(ctx, sub, s.dataRoaming)) return "could not change data roaming"
            Log.i(TAG, "SIM slot ${s.slot} data roaming ${s.dataRoaming}")
        }
        if (s.makeDefaultData) {
            val ok = call { SubscriptionManager::class.java.getMethod("setDefaultDataSubId", Int::class.java).invoke(sm, sub) }
            if (!ok) return "could not move mobile data to this SIM"
            Log.i(TAG, "mobile data now on SIM slot ${s.slot}")
        }
        return null
    }

    /** Data roaming off for SIMs never seen before (off by default, per-SIM switch). */
    fun protectNewSims(ctx: Context) {
        val prefs = ctx.getSharedPreferences("sims", Context.MODE_PRIVATE)
        for (info in active(ctx)) {
            val key = "seen_${cardId(info)}"
            if (prefs.getBoolean(key, false)) continue
            if (setDataRoaming(ctx, info.subscriptionId, false)) {
                prefs.edit().putBoolean(key, true).apply()
                Log.i(TAG, "new SIM in slot ${info.simSlotIndex}: data roaming off")
            }
        }
    }

    private fun setDataRoaming(ctx: Context, sub: Int, on: Boolean): Boolean {
        val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub)
        return call { TelephonyManager::class.java.getMethod("setDataRoamingEnabled", Boolean::class.java).invoke(tm, on) }
    }

    /** VoLTE/VoNR registered for voice (ImsMmTelManager.isAvailable, @SystemApi). Best effort. */
    private fun volte(ctx: Context, sub: Int): Boolean = runCatching {
        val cls = Class.forName("android.telephony.ims.ImsMmTelManager")
        val mgr = cls.getMethod("createForSubscriptionId", Int::class.java).invoke(null, sub)
        val isAvailable = cls.getMethod("isAvailable", Int::class.java, Int::class.java)
        // CAPABILITY_TYPE_VOICE = 1; REGISTRATION_TECH_LTE = 0, REGISTRATION_TECH_NR = 3
        isAvailable.invoke(mgr, 1, 0) as Boolean || isAvailable.invoke(mgr, 1, 3) as Boolean
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun number(ctx: Context, info: SubscriptionInfo): String {
        @Suppress("DEPRECATION")
        val n = if (android.os.Build.VERSION.SDK_INT >= 33)
            ctx.getSystemService(SubscriptionManager::class.java).getPhoneNumber(info.subscriptionId)
        else info.number.orEmpty()
        if (n.isBlank()) return ""
        return android.telephony.PhoneNumberUtils.formatNumberToE164(n, info.countryIso.orEmpty().uppercase()) ?: ""
    }

    /** Stable id for the SIM card (ICCID hash; ICCID needs READ_PRIVILEGED_PHONE_STATE). Never the IMEI. */
    @SuppressLint("MissingPermission")
    private fun cardId(info: SubscriptionInfo): String {
        val iccid = runCatching { info.iccId }.getOrNull().orEmpty()
        val src = iccid.ifEmpty { "sub${info.subscriptionId}" }
        return MessageDigest.getInstance("SHA-256").digest(src.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
    }

    private fun call(block: () -> Unit): Boolean =
        runCatching(block).onFailure { Log.w(TAG, "SIM setting failed: ${(it.cause ?: it)}") }.isSuccess
}
