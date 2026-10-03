plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

/**
 * Generates the baseline profile shipped in the release APK: the methods ART compiles ahead of
 * time. AndroidX libraries bring their own; this covers Chatter's start and reading the chat.
 *
 * Not run by CI: `./gradlew :app:generateReleaseBaselineProfile` with a phone attached writes
 * app/src/release/generated/baselineProfiles/, which is committed. ChatBenchmark measures its
 * effect: `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`.
 */
android {
    namespace = "dev.chatter.app.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

