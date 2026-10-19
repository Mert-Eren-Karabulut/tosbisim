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
        versionCode = 32
        versionName = "0.11.0"
    }
    sourceSets["main"].apply {
        // Device profiles (repo-root profiles/) ship as assets, e.g. assets/qcom/sm8250-samsung-r8q.json
        assets.srcDir("$rootDir/../../profiles")
        // The Magisk module (no APK inside) the app installs itself once rooted: host/magisk/pack-embed.sh
        assets.srcDir("src/embed/assets")
        // Shared host<->client protocol (repo-root protocol/, MIT)
        proto { srcDir("$rootDir/../../protocol") }
    }
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
}
