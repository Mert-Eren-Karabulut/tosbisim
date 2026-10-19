// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dev.simrelay.protocol.v1.Update
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Calendar
import java.util.zip.ZipFile

/**
 * The host app updates itself from api.tosbisim.com (protocol/README.md "Updates"): check daily, download and verify
 * the newest APK (sha256 + same signing key as the installed app), tell the iPhone, then install on the user's word
 * or by itself between 02:00 and 05:00 once the iPhone has been told. Never during a call. "Later" = one day.
 * After an install the new version swaps in its own simrelayd if it differs (no call up).
 */
object Updater {
    private const val TAG = "SimRelayHost"
    private const val DAY_MS = 24 * 3600_000L
    private val thread = HandlerThread("updater").apply { start() }
    private val h = Handler(thread.looper)
    private lateinit var app: Context
    private lateinit var changed: () -> Unit

    @Volatile private var state = Update.State.IDLE
    @Volatile private var available: JSONObject? = null     // newest release (from the API)
    @Volatile private var notes: List<Update.Note> = emptyList()
    @Volatile private var error = ""

    private fun prefs() = app.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val apkFile get() = File(app.filesDir, "update.apk")

    fun start(ctx: Context, changed: () -> Unit) {
        app = ctx.applicationContext
        this.changed = changed
        h.postDelayed({ check() }, 10_000)                  // soon: after an update the iPhone wants "up to date" quickly
        h.postDelayed(nightly, 10 * 60_000)
        h.post { swapDaemonIfNeeded() }
    }

    fun status(): Update {
        val a = available
        val b = Update.newBuilder()
            .setCurrentVersion(BuildConfigInfo.version(app)).setCurrentCode(myCode())
            .setState(state).addAllNotes(notes).setError(error)
            .setDeferredUntilMs(prefs().getLong("deferred_until", 0)).setCheckedMs(prefs().getLong("checked", 0))
        if (a != null) b.setAvailableVersion(a.optString("version")).setAvailableCode(a.optInt("code"))
        return b.build()
    }

    /** A client has seen the status with a ready update: an hour later it may install at night. */
    fun informed() {
        if (state == Update.State.READY && !prefs().getBoolean("informed_${available?.optInt("code")}", false)) {
            prefs().edit().putBoolean("informed_${available?.optInt("code")}", true)
                .putLong("informed_at_${available?.optInt("code")}", System.currentTimeMillis()).apply()
            Log.i(TAG, "update ${available?.optString("version")}: the iPhone has been told")
        }
    }

    fun action(a: dev.simrelay.protocol.v1.UpdateAction.Action, done: (String?) -> Unit) = h.post {
        when (a) {
            dev.simrelay.protocol.v1.UpdateAction.Action.CHECK -> { check(); done(null) }
            dev.simrelay.protocol.v1.UpdateAction.Action.LATER -> {
                prefs().edit().putLong("deferred_until", System.currentTimeMillis() + DAY_MS).apply()
                Log.i(TAG, "update postponed by a day"); changed(); done(null)
            }
            dev.simrelay.protocol.v1.UpdateAction.Action.INSTALL_NOW -> done(install())
            else -> done(null)
        }
    }

    private fun myCode(): Int = runCatching {
        @Suppress("DEPRECATION") app.packageManager.getPackageInfo(app.packageName, 0).versionCode
    }.getOrDefault(0)

    private fun set(s: Update.State, err: String = "") { state = s; error = err; changed() }

    private fun check() {
        if (state == Update.State.DOWNLOADING || state == Update.State.INSTALLING) return
        set(Update.State.CHECKING)
        // The check also tells the server which version this install runs (admin Fleet page; hashed id).
        val r = Api.postJson("/releases/host/check", JSONObject().put("install_id", Api.installId(app))
            .put("version", BuildConfigInfo.version(app)).put("code", myCode())
            .put("manufacturer", android.os.Build.MANUFACTURER).put("model", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT))
        if (r == null) {
            // A failed check must not throw away an update already downloaded and verified (it would block the
            // night install and "Update now" for a day).
            if (available != null && apkFile.exists()) { set(Update.State.READY, ""); return }
            set(Update.State.FAILED, "could not reach the update server"); return
        }
        prefs().edit().putLong("checked", System.currentTimeMillis()).apply()
        val list = r.optJSONArray("releases")
        // The notes ride in every HostStatus: newest 3 versions, each capped, so a host far behind never builds a status
        // too big for one frame (that would crash-loop the app).
        notes = (0 until minOf(list?.length() ?: 0, 3)).map { list!!.getJSONObject(it) }.map {
            Update.Note.newBuilder().setVersion(it.optString("version"))
                .setEn(it.optJSONObject("notes")?.optString("en").orEmpty().take(600))
                .setTr(it.optJSONObject("notes")?.optString("tr").orEmpty().take(600)).build()
        }
        val latest = list?.optJSONObject(0)
        if (latest == null || latest.optInt("code") <= myCode()) { available = null; set(Update.State.IDLE); return }
        available = latest
        download(latest)
    }

    private fun download(r: JSONObject) {
        val apk = r.optJSONObject("apk") ?: return set(Update.State.FAILED, "release has no APK")
        val sha = apk.optString("sha256")
        if (apkFile.exists() && sha256(apkFile) == sha) return verified()
        set(Update.State.DOWNLOADING)
        val tmp = File(app.filesDir, "update.part")
        val ok = runCatching {
            val c = URL(apk.getString("url")).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            check(sha256(tmp) == sha) { "checksum mismatch" }
            tmp.renameTo(apkFile)
        }
        ok.exceptionOrNull()?.let { tmp.delete(); return set(Update.State.FAILED, "download failed: ${it.message}") }
        verified()
    }

    /** Same package, newer, and signed with the installed app's key (Android would refuse anything else). */
    private fun verified() {
        val pm = app.packageManager
        val info = pm.getPackageArchiveInfo(apkFile.path, PackageManager.GET_SIGNING_CERTIFICATES)
        val code = info?.longVersionCode ?: 0L
        val mine = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val theirs = info?.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val ours = mine.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        when {
            info?.packageName != app.packageName -> { apkFile.delete(); set(Update.State.FAILED, "not a TosbiSim Host package") }
            // The APK itself must be the advertised, newer version (else it would "install" the same version nightly).
            code <= myCode() || code != (available?.optLong("code") ?: -1L) ->
                { apkFile.delete(); set(Update.State.FAILED, "the downloaded package isn't version ${available?.optString("version")}") }
            theirs == null || theirs != ours -> set(Update.State.FAILED,
                "the update is signed with a different key than this installation (a development build?)")
            else -> { Log.i(TAG, "update ${available?.optString("version")} downloaded and verified"); set(Update.State.READY) }
        }
    }

    /** null = started (the app is replaced and restarts), else why not. */
    private fun install(): String? {
        if (state != Update.State.READY) return "no update ready"
        if (CallRegistry.snapshot().any { it.state != dev.simrelay.protocol.v1.CallEvent.State.ENDED }) return "after the call"
        set(Update.State.INSTALLING)
        Log.i(TAG, "installing update ${available?.optString("version")}")
        // The install replaces this process within a second or two: let "Installing" reach the iPhone first.
        Thread.sleep(1500)
        if (CallRegistry.snapshot().any { it.state != dev.simrelay.protocol.v1.CallEvent.State.ENDED }) {
            set(Update.State.READY, ""); return "after the call"     // a call started during the pause
        }
        val tmp = "/data/local/tmp/tosbisim-update.apk"
        val out = Root.sh("cp ${Root.q(apkFile.path)} $tmp && chmod 644 $tmp && pm install -r $tmp; rc=\$?; rm -f $tmp; exit \$rc", 180_000).out.trim()
        // Success replaces this process before we get here, usually; if we're still alive, it failed.
        Log.e(TAG, "update install: $out")
        val why = if (out.contains("Permission denied", true) || out.contains("not allowed", true))
            "root was refused: allow TosbiSim Host in Magisk (the module grants it at the next boot)" else "install failed: ${out.take(120)}"
        set(Update.State.READY, why)               // still downloaded and verified: "Update now" can retry
        return why
    }

    /** Night window: install a ready update the iPhone has been told about, unless postponed, never mid-call. */
    private val nightly = object : Runnable {
        override fun run() {
            h.postDelayed(this, 10 * 60_000)
            val now = System.currentTimeMillis()
            if (now - prefs().getLong("checked", 0) > DAY_MS) check()
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val code = available?.optInt("code") ?: return
            // Told at least an hour ago, so the user had time to say "Later" (18 Oct: it installed 4 min after telling).
            val toldAt = prefs().getLong("informed_at_$code", 0)
            if (state == Update.State.READY && hour in 2..4 && now >= prefs().getLong("deferred_until", 0) &&
                prefs().getBoolean("informed_$code", false) && toldAt > 0 && now - toldAt >= 3600_000L) {
                Log.i(TAG, "night window: installing the update")
                install()
            }
        }
    }

    /** After an update: the embedded module's simrelayd replaces the running one if they differ (no call up). */
    private fun swapDaemonIfNeeded() {
        if (CallRegistry.snapshot().any { it.state != dev.simrelay.protocol.v1.CallEvent.State.ENDED }) {
            h.postDelayed({ swapDaemonIfNeeded() }, 60_000); return
        }
        runCatching {
            val zip = File(app.cacheDir, "module.zip")
            app.assets.open("module/tosbisim-host-module.zip").use { i -> zip.outputStream().use { o -> i.copyTo(o) } }
            val fresh = File(app.cacheDir, "simrelayd")
            ZipFile(zip).use { z -> z.getInputStream(z.getEntry("simrelayd")).use { i -> fresh.outputStream().use { o -> i.copyTo(o) } } }
            val installedSha = Root.sh("sha256sum /data/adb/modules/simrelay-host/simrelayd").out.substringBefore(' ')
            if (installedSha.isNotEmpty() && installedSha != sha256(fresh)) {
                val m = "/data/adb/modules/simrelay-host"
                Root.sh("cp ${Root.q(fresh.path)} $m/simrelayd.tmp && chmod 755 $m/simrelayd.tmp && " +
                    "mv -f $m/simrelayd.tmp $m/simrelayd && kill \$(pidof simrelayd)")
                Log.i(TAG, "simrelayd updated from this app version (restarts in 2 s)")
                h.postDelayed({ Daemon.reconnect() }, 4000)
            }
            refreshModuleFiles(zip)
            fresh.delete(); zip.delete()
        }.onFailure { Log.w(TAG, "simrelayd update check: $it") }
    }

    /**
     * The module's other files come from the version that installed the module, not from updates: the S20 FE kept a
     * 0.9.x service.sh after self-updating to 0.10 (no DND access for the volume fix, 18 Oct). Refresh them from this
     * version's embedded module when they differ; they take effect at the next boot, like a fresh install. The privapp
     * whitelist is only ever added to by new versions, so replacing it can't drop a permission this APK requests.
     */
    private fun refreshModuleFiles(zip: File) {
        val m = "/data/adb/modules/simrelay-host"
        val files = listOf("service.sh", "sepolicy.rule", "module.prop", "system/etc/permissions/privapp-permissions-dev.simrelay.host.xml")
        ZipFile(zip).use { z ->
            for (name in files) {
                val e = z.getEntry(name) ?: continue
                val tmp = File(app.cacheDir, name.substringAfterLast('/'))
                z.getInputStream(e).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                val have = Root.sh("sha256sum ${Root.q("$m/$name")} 2>/dev/null").out.substringBefore(' ')
                if (have != sha256(tmp)) {
                    val mode = if (name == "service.sh") "755" else "644"
                    val dst = "$m/$name"
                    val ok = Root.sh("cp ${Root.q(tmp.path)} ${Root.q("$dst.tmp")} && chmod $mode ${Root.q("$dst.tmp")} && mv -f ${Root.q("$dst.tmp")} ${Root.q(dst)}").ok
                    Log.i(TAG, "module $name ${if (ok) "updated (next boot)" else "update FAILED"}")
                }
                tmp.delete()
            }
        }
    }

    private fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { i -> val buf = ByteArray(65536); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}
