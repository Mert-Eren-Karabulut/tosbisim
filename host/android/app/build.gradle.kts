import com.google.protobuf.gradle.proto
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

// Release signing: the key lives outside the repo (~/.tosbisim/signing.properties + release.jks). Every
// update must be signed with it, or phones refuse to install it over the previous version. Back it up.
val signingProps = Properties().apply {
    val f = file("${System.getProperty("user.home")}/.tosbisim/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.simrelay.host"
    compileSdk = 36
    signingConfigs {
        create("release") {
            if (signingProps.isNotEmpty()) {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false      // reflection on system APIs + protobuf-lite: no shrinking
            signingConfig = if (signingProps.isNotEmpty()) signingConfigs.getByName("release") else null
        }
    }

    defaultConfig {
        applicationId = "dev.simrelay.host"
        minSdk = 30
        targetSdk = 33   // TODO(M6): 34+ needs foregroundServiceType declarations
        versionCode = 39
        versionName = "0.12.3"
    }
    sourceSets["main"].apply {
        // Device profiles (repo-root profiles/) ship as assets, e.g. assets/qcom/sm8250-samsung-r8q.json
        assets.srcDir("$rootDir/../../profiles")
        // The Magisk module (no APK inside) the app installs itself once rooted: host/magisk/pack-embed.sh
        assets.srcDir("src/embed/assets")
        // Shared host<->client protocol (repo-root protocol/, MIT)
        proto { srcDir("$rootDir/../../protocol") }
    }
    // Not for the phone: the device index (~4 MB, served by api.tosbisim.com) and the table's hand-written source.
    // Appended to aapt's default ignore pattern.
    androidResources.ignoreAssetsPattern =
        "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!devices.json:!support.src.json"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.31.1" }
    generateProtoTasks {
        all().forEach { task -> task.builtins { create("java") { option("lite") } } }
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:4.31.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // remote mode WebSocket (Apache-2.0)
    testImplementation("junit:junit:4.13.2")
}

// Every permission the app requests is either on the module's privapp allowlist or an ordinary (normal/runtime) one
// reviewed here. A privileged permission missing from the allowlist stops some phones from booting once the module
// installs us as a priv-app (enforce mode): a new library or manifest line must fail the build, not a phone (audit,
// 19 Oct 2026).
val ordinaryPermissions = setOf(
    "ACCESS_NETWORK_STATE", "ACCESS_NOTIFICATION_POLICY", "ACCESS_WIFI_STATE", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT",
    "CALL_PHONE", "CHANGE_WIFI_STATE", "FOREGROUND_SERVICE", "INTERNET", "POST_NOTIFICATIONS", "READ_PHONE_STATE",
    "READ_SMS", "RECEIVE_BOOT_COMPLETED", "RECEIVE_MMS", "RECEIVE_SMS", "RECEIVE_WAP_PUSH", "SEND_SMS", "WAKE_LOCK",
    "WRITE_SETTINGS", "BLUETOOTH", "BLUETOOTH_ADMIN",
).map { "android.permission.$it" }.toSet()
tasks.matching { it.name.startsWith("process") && it.name.endsWith("MainManifest") }.configureEach {
    doLast {
        val manifest = outputs.files.asFileTree.matching { include("**/AndroidManifest.xml") }.files.firstOrNull() ?: return@doLast
        val requested = Regex("""uses-permission[^>]*android:name="([^"]+)"""").findAll(manifest.readText()).map { it.groupValues[1] }.toSet()
        val allowlist = rootProject.file("../magisk/template/system/etc/permissions/privapp-permissions-dev.simrelay.host.xml")
        val privileged = Regex("""permission name="([^"]+)"""").findAll(allowlist.readText()).map { it.groupValues[1] }.toSet()
        val unknown = requested - privileged - ordinaryPermissions
        if (unknown.isNotEmpty()) throw GradleException(
            "Permissions neither in the privapp allowlist nor reviewed as ordinary: $unknown. Add privileged ones to " +
                "${allowlist.name}, ordinary ones to ordinaryPermissions in app/build.gradle.kts.")
    }
}
