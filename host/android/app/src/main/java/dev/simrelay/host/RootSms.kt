// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.util.Log

/**
 * Texts to short codes without Android's question. Before an app's first text to a number that may be a premium short
 * code (in Turkey every number of 1 to 5 digits: banks, operators, "send KALAN to ...") Android asks on the phone's
 * own screen whether to send it, and waits. Nobody sees the host's screen: the text stayed "sending" and failed 32
 * minutes later with "short code not allowed" (a Redmi Note 9 Pro, 10 Nov 2026). The person typed that number in
 * TosbiSim themselves, so the answer is "always allow" (Settings > Special app access > Premium SMS access).
 *
 * Only the system may give that answer for an app (SmsUsageMonitor checks the caller), and the permission that skips
 * the question is a privileged one the module's allowlist doesn't have ([RootBt]); so it is set as root through
 * app_process, once per start and again when a text is refused for it:
 *
 *   CLASSPATH=<apk> app_process /system/bin dev.simrelay.host.RootSms allow|ask <package>
 *     -> "ok <subscriptions set>" | "error ..."        ("ask": Android's question back, for the test rig)
 */
object RootSms {
    private const val ASK_USER = 1                                   // SmsManager.PREMIUM_SMS_CONSENT_ASK_USER
    private const val ALWAYS_ALLOW = 3                               // SmsManager.PREMIUM_SMS_CONSENT_ALWAYS_ALLOW

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(args.size == 2 && args[0] in setOf("allow", "ask")) { "usage" }
            val pkg = args[1]
            val answer = if (args[0] == "allow") ALWAYS_ALLOW else ASK_USER
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "isms") as IBinder
            val isms = Class.forName("com.android.internal.telephony.ISms\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val int = Int::class.javaPrimitiveType
            // Each SIM's phone keeps its own copy of the answers
            val subs = runCatching {
                systemContext().getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }
            }.getOrDefault(emptyList())
            var set = 0
            for (sub in subs) {
                isms.javaClass.getMethod("setPremiumSmsPermissionForSubscriber", int, String::class.java, int).invoke(isms, sub, pkg, answer)
                val now = isms.javaClass.getMethod("getPremiumSmsPermissionForSubscriber", int, String::class.java).invoke(isms, sub, pkg)
                require(now == answer) { "subscription $sub answers $now" }
                set++
            }
            if (subs.isEmpty()) {                                   // no SIM yet: the default phone's copy
                isms.javaClass.getMethod("setPremiumSmsPermission", String::class.java, int).invoke(isms, pkg, answer)
            }
            println("ok $set")
        } catch (t: Throwable) {
            println("error ${t.cause ?: t}")
        }
    }

    /** A context in this bare process (as [RootCallState]): telephony's service handles come with the mainline modules. */
    private fun systemContext(): Context {
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper()
        val at = Class.forName("android.app.ActivityThread")
        runCatching { at.getMethod("initializeMainlineModules").invoke(null) }
        val thread = at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }

    // ------------------------------------------------------------------------------------------ in the host app

    /** What the last try said ("ok 1", "error ..."), for a failed text's report. */
    @Volatile var last = ""; private set

    /** From the app, off the caller's thread ([answer]: "allow"; "ask" only from the test rig). Says nothing new when nothing changed. */
    fun set(ctx: Context, answer: String, why: String) = Thread {
        val r = Root.appProcess(ctx.applicationContext, RootSms::class.java.name, answer, ctx.packageName, timeoutMs = 20_000).last
        if (r != last || answer != "allow") Log.i("SimRelayHost", "texts to short codes ($why): $answer -> $r")
        last = r
    }.apply { isDaemon = true }.start()
}
