import java.util.Properties

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

/**
 * Generates the baseline profile shipped in the release APK: the list of methods ART compiles
 * ahead of time instead of interpreting on first use. The AndroidX libraries bring their own, so
 * this one is only about Chatter's share of a cold start.
 *
 * It is not built or run by CI — `./gradlew :app:generateReleaseBaselineProfile` with a phone attached
 * writes app/src/release/generated/baselineProfiles/, and that file is committed. ChatBenchmark
 * shows what the profile is worth: `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`.
 *
 * Everything past the login needs a Twitch token, which local.properties holds on the maintainer's
 * machine and nowhere else: `profiling.token=...`, and `profiling.channel=...` for a busy channel
 * other than the default. `./gradlew :baselineprofile:profilingTokenUrl` says where to get one.
 */
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "dev.chatter.app.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Handed to the tests when they run, never built into them; see Journeys.kt.
        localProps.getProperty("profiling.token")?.let { testInstrumentationRunnerArguments["chatterToken"] = it }
        localProps.getProperty("profiling.channel")?.let { testInstrumentationRunnerArguments["chatterChannel"] = it }
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

/**
 * Twitch's login page for Chatter, answering with a token for the profiling builds. Twitch sends
 * the browser on to http://localhost, which does not load, and the address it fails to load
 * carries the token: `#access_token=<this>&...`. Reading the chat is all the journeys do, so that
 * is all the token may do.
 */
tasks.register("profilingTokenUrl") {
    description = "Prints where to get the Twitch token the baseline profile journeys log in with."
    val clientId = localProps.getProperty("twitch.clientId").orEmpty()
    doLast {
        println(
            "https://id.twitch.tv/oauth2/authorize?response_type=token&client_id=$clientId" +
                "&redirect_uri=http://localhost&scope=chat:read+user:read:emotes+user:read:follows",
        )
    }
}
