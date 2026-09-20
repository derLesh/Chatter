plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

/**
 * Generates the baseline profile shipped in the release APK: the list of methods ART compiles
 * ahead of time instead of interpreting on first use. The AndroidX libraries bring their own, so
 * this one is only about Chatter's share of a cold start.
 *
 * It is not built or run by CI — `./gradlew :app:generateBaselineProfile` with a phone attached
 * writes app/src/release/generated/baselineProfiles/, and that file is committed.
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
