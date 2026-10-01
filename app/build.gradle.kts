import com.android.build.api.variant.BuildConfigField
import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.baselineprofile)
}

// The Twitch Client ID lives in local.properties (not committed): twitch.clientId=...
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/** An environment variable, empty ones read as if they were not set at all — CI sets those. */
fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

// CI has no local.properties, so there the ID arrives as an environment variable from a secret.
val twitchClientId: String = localProps.getProperty("twitch.clientId").orEmpty()
    .ifBlank { env("TWITCH_CLIENT_ID").orEmpty() }

// The upload key the release workflow signs with. It hands the keystore over as a file it decodes
// from a secret, so nothing about the key is ever committed. Without it — a release build on a dev
// machine — the debug key below keeps the APK installable, the way it has always been.
val uploadKeystore: String? = env("CHATTER_KEYSTORE_FILE")

// Play rejects anything signed with the debug key, and the first upload decides which key the app
// is allowed to be updated with for good. So a build meant for Play sets this and stops outright
// rather than quietly falling back to the debug key the way a build on a dev machine may.
val requireUploadKey: Boolean = env("CHATTER_REQUIRE_UPLOAD_KEY") != null

// The version comes from the release tooling in the root build: "./gradlew releaseVersion" works it
// out from the entries in pending-changelog/, so nobody edits a version by hand.
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

/**
 * Whether supporting Chatter is a thing yet.
 *
 * GitHub Sponsors is not set up, so there is nothing to link to, nothing to claim and no list to
 * fetch — and an app that asks for a list nobody serves says so on the screen. It is all built and
 * tested and waiting: turning it on is this one line.
 */
val sponsoring = false

/**
 * Whether this is the APK people install themselves from the GitHub releases, rather than the
 * build for Play. The release workflow says so with `-Pdistribution=github`, and forgetting it can
 * only ever leave out what Play must not see, never put it in.
 */
val sideloaded = (project.findProperty("distribution") as String?) == "github"

/**
 * Whether this build may show the way to GitHub Sponsors.
 *
 * Google Play wants payments that happen in an app to go through its own billing, and a link
 * straight past it is the kind of thing a review takes issue with — so the Play build does not
 * carry one, and the APK people install themselves does. The default is the careful one: pass
 * `-Pdistribution=github` for the sideload APK, and forgetting it can only ever leave the link
 * out, never put it where it must not be.
 */
val sponsorLink = sponsoring && sideloaded

/**
 * The two builds the baselineprofile plugin adds: the one the baseline profile is recorded on and
 * the one the macrobenchmark measures. They are an app of their own, for the same reason as the
 * microbenchmark — the connected test wipes it to show the login and uninstalls it afterwards —
 * and the only builds that can be told to read a channel as a guest (see MainActivity), which is
 * how the journeys get past the login screen to a chat.
 */
val profilingBuildTypes = listOf("nonMinifiedRelease", "benchmarkRelease")

android {
    namespace = "dev.chatter.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.chatter.app"
        minSdk = 33
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("version")
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"$twitchClientId\"")
        buildConfigField("boolean", "SPONSORING", "$sponsoring")
        // True only in the profilingBuildTypes.
        buildConfigField("boolean", "PROFILING", "false")

        // The microbenchmark in androidTest (see testBuildType below). Emulators are let through
        // because CI has nothing else; their numbers only mean something next to each other, which
        // is how the Benchmark workflow uses them.
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    signingConfigs {
        if (uploadKeystore != null) {
            // All four or none: a keystore with a missing password fails deep inside the signing
            // task, where the message says nothing about which secret was left unset.
            val missing = listOf(
                "CHATTER_KEYSTORE_PASSWORD" to env("CHATTER_KEYSTORE_PASSWORD"),
                "CHATTER_KEY_ALIAS" to env("CHATTER_KEY_ALIAS"),
                "CHATTER_KEY_PASSWORD" to env("CHATTER_KEY_PASSWORD"),
            ).filter { it.second == null }.map { it.first }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "CHATTER_KEYSTORE_FILE is set, but $missing ${if (missing.size == 1) "is" else "are"} not. " +
                        "The upload key needs the keystore, its password, the alias and the key password.",
                )
            }
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = env("CHATTER_KEYSTORE_PASSWORD")
                keyAlias = env("CHATTER_KEY_ALIAS")
                keyPassword = env("CHATTER_KEY_PASSWORD")
            }
        } else if (requireUploadKey) {
            throw GradleException(
                "CHATTER_REQUIRE_UPLOAD_KEY is set, so this build is meant for Play, but there is no " +
                    "upload keystore in CHATTER_KEYSTORE_FILE. Signing it with the debug key would " +
                    "produce an artifact Play refuses.",
            )
        }
    }

    buildTypes {
        debug {
            // Whoever is building the app themselves is the one who wants to see it.
            buildConfigField("boolean", "SPONSOR_LINK", "$sponsoring")
            buildConfigField("boolean", "UPDATE_CHECK", "true")
        }
        release {
            buildConfigField("boolean", "SPONSOR_LINK", "$sponsorLink")
            // Play updates its own installs and forbids an app to point anywhere else for an
            // update, so only the sideloaded APK asks GitHub whether a newer version is out.
            buildConfigField("boolean", "UPDATE_CHECK", "$sideloaded")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The upload key when there is one, otherwise the debug key, so a release build can
            // still be installed directly for testing.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
        // What the microbenchmark runs against: the debug build without being debuggable, because a
        // debuggable app runs its code with the optimizations switched off and would be measuring
        // something no user ever runs. Not minified either, so the benchmark can reach the classes it
        // calls, which R8 would rename or throw away.
        create("microbenchmark") {
            initWith(getByName("debug"))
            isDebuggable = false
            // An app of its own: a connected test uninstalls what it tested when it is done, and
            // that must not be the Chatter somebody is logged in to on the same phone.
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    testBuildType = "microbenchmark"
    // Has the app compiled ahead of time when a connected test installs it, the way the benchmark
    // plugin does for a benchmark module. Interpreted and half-compiled code would measure the JIT.
    experimentalProperties["android.experimental.force-aot-compilation"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // The repositories write a line to the log when a provider does not answer, and that
            // is exactly the path the tests walk. android.util.Log is not there in a plain JVM
            // test and throws unless its methods are stubbed out to do nothing.
            isReturnDefaultValues = true
        }
    }
}

/**
 * The changelog the app shows under Settings -> About is the repository's own CHANGELOG.md, so
 * there is never a second copy to keep in step. It ships as an asset named changelog.md.
 */
abstract class CopyChangelog : DefaultTask() {
    @get:InputFile
    abstract val changelog: RegularFileProperty

    /** Filled in by the Android variant API, which also wires the build to depend on this task. */
    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @TaskAction
    fun run() {
        changelog.get().asFile.copyTo(assets.get().asFile.resolve("changelog.md"), overwrite = true)
    }
}

val copyChangelog = tasks.register<CopyChangelog>("copyChangelog") {
    description = "Ships the repo's CHANGELOG.md as an app asset."
    changelog.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
}

androidComponents {
    // Unit tests only come with the build type the device tests run against, and that is the
    // microbenchmark's now. They are about the debug build, as they always were: CI and everyone
    // else run testDebugUnitTest.
    beforeVariants(selector().withBuildType("debug")) { variant ->
        (variant as HasHostTestsBuilder).hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
    beforeVariants(selector().withBuildType("microbenchmark")) { variant ->
        (variant as HasHostTestsBuilder).hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
    for (buildType in profilingBuildTypes) {
        onVariants(selector().withBuildType(buildType)) { variant ->
            variant.applicationId.set("dev.chatter.app.profiling")
            variant.buildConfigFields?.put("PROFILING", BuildConfigField("boolean", "true", null))
        }
    }
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyChangelog, CopyChangelog::assets)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigationevent.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    // Linear-time regular expressions for the user's rules; see RuleEngine.
    implementation(libs.re2j)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // Lets the tests drive the coroutines and the clock of anything built around a dispatcher.
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.benchmark.junit4)
    // The benchmark runs in the process of the app it measures, and brings an activity and a
    // permission it needs there. Only the build it measures gets them; see testBuildType.
    "microbenchmarkImplementation"(libs.androidx.benchmark.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)

    // The profile the :baselineprofile module generates, compiled into the release APK.
    baselineProfile(project(":baselineprofile"))
}
