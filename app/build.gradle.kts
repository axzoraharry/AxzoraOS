import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * Release signing credentials, kept out of the repository.
 *
 * Android identifies an app by the key it was signed with: an update signed
 * with a different key is refused outright. So this key has to survive for
 * the life of the app — losing it means no existing install can ever be
 * updated again. It lives in keystore.properties, which is gitignored.
 *
 * When the file is absent — a fresh clone, or CI without the secret — the
 * release build simply falls back to unsigned rather than failing, so the
 * project still builds for anyone who only needs a debug APK.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null &&
    rootProject.file(keystoreProps.getProperty("storeFile", "")).exists()

android {
    namespace = "com.axzora.os"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.axzora.os"
        minSdk = 26
        targetSdk = 34
        versionCode = 208
        versionName = "18.128"

        ndk {
            // Every phone sold in years is arm64. The release payload ships
            // arm64 only: dropping the other ABIs roughly halves the native
            // payload, which is what keeps the update package under GitHub's
            // 100MB file cap - the store lives in the repo. The debug build
            // adds x86_64 below so the emulator stays testable.
            abiFilters += "arm64-v8a"
        }
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v1 is what older phones verify with; v2/v3 are faster and
                // stronger on modern ones. Enabling all three keeps the APK
                // installable from Android 8 up to current releases.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            // The field devices run this debug build (the OTA channel ships it),
            // so it must stay arm64-only to fit under GitHub's 100MB file cap.
            // Pass -Pemulator to add x86_64 for local emulator testing; that
            // variant is never published.
            if (project.hasProperty("emulator")) ndk { abiFilters += "x86_64" }
        }
        release {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            // Left off deliberately: the app reflects over JSON and links
            // native WebRTC and MediaPipe libraries, so shrinking needs its
            // own testing pass rather than being switched on with signing.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    // Fragment 1.0.0/1.1.0 arrives transitively and sits below the 1.3.0 that the
    // ActivityResult APIs require — the old FragmentActivity failed to call
    // super.onRequestPermissionsResult() and used invalid request codes. Lint's
    // InvalidFragmentVersionForActivityResult flags that as a fatal release
    // error, so lintVitalRelease was failing EVERY release build (the APK still
    // packaged, which is why it looked like it worked). Pinning a modern version
    // fixes the cause rather than suppressing the check.
    implementation("androidx.fragment:fragment:1.5.4")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    // Mr Happy's engines: Porcupine wake word, Vosk offline speech, JNA.
    implementation(files("libs/porcupine-core.jar", "libs/vosk.jar", "libs/jna.jar"))

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Offline peer-to-peer: BLE discovery upgraded to Wi-Fi Direct for transfer.
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // Voice calling. Google's own artifact is unmaintained on Maven; this is
    // the actively published build of the same WebRTC stack.
    implementation("io.getstream:stream-webrtc-android:1.3.8")
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
