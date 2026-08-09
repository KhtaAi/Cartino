import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.google.services)
}

// Auto-restore debug.keystore from debug.keystore.base64 if missing.
// NOTE (AGENTS.md 9 / 15.4): LOCAL development & AI Studio emulator ONLY.
// `debug.keystore` and `debug.keystore.base64` must NEVER be committed to the
// repository (both are covered by .gitignore).
val debugKeystoreFile = file("${rootDir}/debug.keystore")
val debugKeystoreBase64File = file("${rootDir}/debug.keystore.base64")
if (!debugKeystoreFile.exists() && debugKeystoreBase64File.exists()) {
    try {
        val b64Str = debugKeystoreBase64File.readText().replace("\n", "").replace("\r", "").trim()
        val bytes = Base64.getDecoder().decode(b64Str)
        debugKeystoreFile.writeBytes(bytes)
    } catch (e: Exception) {
        logger.warn("Could not decode debug.keystore.base64 automatically: ${e.message}")
    }
}

// ---- Release signing policy (AGENTS.md 6.2 / 15.2) ----
// Release signing MUST come exclusively from CI-injected environment variables
// (GitHub Secrets). There is NO fallback to the debug keystore and NO hardcoded
// default passwords. If a release/bundle task is requested while signing env
// vars are absent, the build FAILS with a clear error instead of silently
// producing a debug-signed "release" artifact.
val releaseStorePath = System.getenv("SIGNING_STORE_FILE") ?: System.getenv("KEYSTORE_PATH")
val hasReleaseSigning = !releaseStorePath.isNullOrBlank()

val requestedTasks = gradle.startParameter.taskNames.joinToString(" ").lowercase()
val isReleaseRequested = requestedTasks.contains("release") || requestedTasks.contains("bundle")
if (isReleaseRequested && !hasReleaseSigning) {
    throw GradleException(
        "Release signing configuration is missing. " +
            "Set SIGNING_STORE_FILE, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS and SIGNING_KEY_PASSWORD " +
            "(in CI these are injected from GitHub Secrets). " +
            "Signing a release build with the debug keystore is FORBIDDEN (see AGENTS.md 15.2)."
    )
}

android {
    namespace = "com.example"
    compileSdk = 36

    val autoVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: System.getenv("VERSION_CODE")?.toIntOrNull() ?: project.findProperty("VERSION_CODE")?.toString()?.toIntOrNull() ?: 26
    val autoVersionName = (findProperty("versionName") as String?) ?: System.getenv("VERSION_NAME") ?: project.findProperty("VERSION_NAME")?.toString() ?: "1.0.26"

    defaultConfig {
        applicationId = "com.aistudio.cartino.app"
        minSdk = 24
        targetSdk = 36
        versionCode = autoVersionCode
        versionName = autoVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        getByName("debug") {
            if (debugKeystoreFile.exists()) {
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            // Environment-only signing. No debug fallback, no default passwords.
            if (hasReleaseSigning) {
                storeFile = file(releaseStorePath!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isCrunchPngs = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.firebase.bom))
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.coil.compose)
    implementation(libs.converter.moshi)
    implementation(libs.firebase.ai)
    implementation(libs.androidx.biometric)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.androidx.security.crypto)
    implementation(libs.zxing.core)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(libs.firebase.appcheck.recaptcha)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logging.interceptor)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    "ksp"(libs.androidx.room.compiler)
    "ksp"(libs.moshi.kotlin.codegen)
}

val decodeVazirmatnFonts = tasks.register("decodeVazirmatnFonts") {
    val srcDir = file("fontsrc")
    val outDir = file("src/main/res/font")
    doLast {
        outDir.mkdirs()
        srcDir.listFiles { f -> f.extension == "b64" }?.forEach { b64 ->
            val clean = b64.readText().replace("\n", "").replace("\r", "").trim()
            File(outDir, b64.nameWithoutExtension + ".ttf").writeBytes(Base64.getDecoder().decode(clean))
        }
    }
}

val decodeBankLogos = tasks.register("decodeBankLogos") {
    val srcDir = file("logosrc")
    val outDir = file("src/main/res/drawable")
    doLast {
        outDir.mkdirs()
        srcDir.listFiles { f -> f.extension == "b64" }?.forEach { b64 ->
            val clean = b64.readText().replace("\n", "").replace("\r", "").trim()
            File(outDir, b64.nameWithoutExtension + ".png").writeBytes(Base64.getDecoder().decode(clean))
        }
    }
}

tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(decodeVazirmatnFonts)
        dependsOn(decodeBankLogos)
    }
}

val buildDirProvider = layout.buildDirectory
tasks.register("copyUniversalToDebug") {
    val buildDir = buildDirProvider
    doLast {
        val debugDir = buildDir.get().asFile.resolve("outputs/apk/debug")
        val universalApk = debugDir.resolve("app-universal-debug.apk")
        val standardDebugApk = debugDir.resolve("app-debug.apk")
        if (universalApk.exists()) {
            universalApk.copyTo(standardDebugApk, overwrite = true)
            println("Successfully copied $universalApk to $standardDebugApk")
        } else {
            println("Universal APK not found at $universalApk")
        }
    }
}

tasks.configureEach {
    if (name == "assembleDebug") {
        finalizedBy("copyUniversalToDebug")
    }
}