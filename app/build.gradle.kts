import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// `-Ppremium=true` (make PREMIUM=1): premium unlocked for good, with no trial and no purchase,
// for our own phones and testers. Never for Play: `make bundle` refuses it.
val premiumUnlocked = providers.gradleProperty("premium").map(String::toBoolean).getOrElse(false)

// `-Ptrial=over` (make TRIAL=over): the trial at that stage whatever the clock says, to see each
// stage on a phone: `untried`, `over`, or the days left (1 to 7). Never for Play either.
val trialStage = providers.gradleProperty("trial").getOrElse("")
require(trialStage in setOf("", "untried", "over") || trialStage.toIntOrNull() in 1..7) {
    "trial must be untried, over, or the days left (1 to 7), not \"$trialStage\""
}

// The upload key, which signs the App Bundle for Play (Play App Signing re-signs what users get):
// from keystore.properties at the root (gitignored, see docs/release.md) or, for CI, from
// LUMEMENT_KEYSTORE, LUMEMENT_KEYSTORE_PASSWORD, LUMEMENT_KEY_ALIAS and LUMEMENT_KEY_PASSWORD.
class UploadKey(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

val uploadKey: UploadKey? = run {
    val file = providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties")).asText.orNull
    val props = Properties().apply { file?.let { load(it.reader()) } }
    fun read(prop: String, env: String): String? =
        props.getProperty(prop)?.takeIf(String::isNotBlank) ?: providers.environmentVariable(env).orNull?.takeIf(String::isNotBlank)
    val store = read("storeFile", "LUMEMENT_KEYSTORE") ?: return@run null
    UploadKey(
        storeFile = rootProject.file(store),
        storePassword = read("storePassword", "LUMEMENT_KEYSTORE_PASSWORD") ?: return@run null,
        keyAlias = read("keyAlias", "LUMEMENT_KEY_ALIAS") ?: return@run null,
        keyPassword = read("keyPassword", "LUMEMENT_KEY_PASSWORD") ?: return@run null,
    )
}

// Only a bundle (`make bundle`, the Play upload) is signed with the upload key. The APKs stay
// debug-signed even with the key here, so `make install-release` and `make soak` keep replacing
// a debug install in place, grants and all: an APK signed with the upload key couldn't, and
// couldn't update a Play install either (Play signs those with its own key).
val forPlay = gradle.startParameter.taskNames.any { it.substringAfterLast(':').startsWith("bundle") }

android {
    namespace = "app.lumement"
    // AndroidX core 1.19 requires compiling against API 37; runtime behaviour is pinned by targetSdk.
    compileSdk = 37

    defaultConfig {
        applicationId = "app.lumement"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("boolean", "PREMIUM", premiumUnlocked.toString())
        buildConfigField("String", "TRIAL", "\"$trialStage\"")
        if (premiumUnlocked) versionNameSuffix = "-premium"
        if (trialStage.isNotEmpty()) versionNameSuffix = "-trial-$trialStage"
    }

    androidResources {
        localeFilters += listOf("en")
    }

    signingConfigs {
        if (uploadKey != null) {
            create("upload") {
                storeFile = uploadKey.storeFile
                storePassword = uploadKey.storePassword
                keyAlias = uploadKey.keyAlias
                keyPassword = uploadKey.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // The upload key for a bundle, debug-signed otherwise (see forPlay above).
            signingConfig = signingConfigs.getByName(if (forPlay && uploadKey != null) "upload" else "debug")
        }
        // `make soak`: the release build as it ships (R8, no logs), plus the one thing a battery run
        // needs, adb's test messages (src/soak: <queries> com.android.shell). Never for Play.
        create("soak") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Constants only: DEBUG (GlowLog compiles away in release, and so does the class), and
        // PREMIUM and TRIAL (premium unlocked, or its trial staged, at build time: see above).
        buildConfig = true
    }

    testOptions {
        // Pure-Kotlin logic only; any android.* call returns a default instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }
}

// What keeps a bundle from Play: it must be signed with the upload key, and never unlock premium
// or stage its trial for everyone (`make bundle` refuses those too, this catches a bare gradlew).
val bundleProblem: String? = when {
    uploadKey == null ->
        "No upload key: bundleRelease builds the Play upload, which must be signed with it. Put " +
            "storeFile, storePassword, keyAlias and keyPassword in keystore.properties at the project root, or " +
            "set LUMEMENT_KEYSTORE, LUMEMENT_KEYSTORE_PASSWORD, LUMEMENT_KEY_ALIAS and LUMEMENT_KEY_PASSWORD. " +
            "docs/release.md says how to make the key."
    !uploadKey.storeFile.isFile -> "The upload keystore ${uploadKey.storeFile} isn't there (keystore.properties or LUMEMENT_KEYSTORE)."
    !forPlay -> "Run the bundle task by its name (bundleRelease or make bundle): only then is it signed with the upload key."
    premiumUnlocked -> "-Ppremium=true unlocks premium for everyone: never build it for Play."
    trialStage.isNotEmpty() -> "-Ptrial=$trialStage fixes the trial for everyone: never build it for Play."
    else -> null
}

val checkPlayBundle = tasks.register("checkPlayBundle") {
    description = "Fails a Play bundle without the upload key, or with premium unlocked or its trial staged."
    val problem = bundleProblem
    doLast { if (problem != null) throw GradleException(problem) }
}
tasks.named { it == "bundleRelease" }.configureEach { dependsOn(checkPlayBundle) }

// The release APK is for testing: say once per build when there is no key for the Play bundle yet.
val uploadKeyNotice = tasks.register("uploadKeyNotice") {
    val missing = uploadKey == null
    onlyIf { missing }
    doLast {
        logger.warn("w: No upload key: app-release.apk is debug-signed (as always, for testing), and make bundle will refuse. See docs/release.md.")
    }
}
tasks.named { it == "assembleRelease" }.configureEach { dependsOn(uploadKeyNotice) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.palette.ktx)
    // Purchases go to the Play Store app over IPC. Its telemetry (datatransport) is left out: it
    // would add INTERNET and background upload jobs, and billing skips logging without it.
    implementation(libs.billing) {
        exclude(group = "com.google.android.datatransport")
    }
    // Google's own review card, asked for once (dashboard/ReviewAsk.kt); also through the Play Store app.
    implementation(libs.play.review)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}
