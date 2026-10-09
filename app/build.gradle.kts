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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Debug-signed so the first release build can be sideloaded for testing.
            // Replace with a real signingConfig before publishing.
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
