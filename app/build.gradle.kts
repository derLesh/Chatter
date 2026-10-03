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

// The Twitch Client ID is in local.properties (not committed): twitch.clientId=...
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/** An environment variable; empty counts as unset, since CI sets empty ones. */
fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

// CI has no local.properties and passes the ID from a secret.
val twitchClientId: String = localProps.getProperty("twitch.clientId").orEmpty()
    .ifBlank { env("TWITCH_CLIENT_ID").orEmpty() }

// The upload keystore, decoded from a secret by the release workflow. Without it (a release build
// on a dev machine) the debug key below keeps the APK installable.
val uploadKeystore: String? = env("CHATTER_KEYSTORE_FILE")

// Play rejects debug-signed builds, and the first upload fixes the signing key for good. Builds for
// Play set this and fail instead of falling back to the debug key.
val requireUploadKey: Boolean = env("CHATTER_REQUIRE_UPLOAD_KEY") != null

// Written by "./gradlew releaseVersion" from the entries in pending-changelog/; never edited by
// hand.
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

/**
 * Whether supporting Chatter is enabled. Off until GitHub Sponsors is set up: nothing to link to,
 * claim or fetch. Everything is built and tested; enabling it is this line.
 */
val sponsoring = false

/**
 * Whether this is the APK installed from GitHub releases rather than the Play build. The release
 * workflow passes `-Pdistribution=github`; forgetting it can only leave out what Play must not see.
 */
val sideloaded = (project.findProperty("distribution") as String?) == "github"

/**
 * Whether this build may link to GitHub Sponsors. Play requires in-app payments to use its billing,
 * so only the sideloaded APK carries the link. Forgetting `-Pdistribution=github` can only leave
 * the link out.
 */
val sponsorLink = sponsoring && sideloaded

/**
 * The two build types the baselineprofile plugin adds: the one the profile is recorded on and the
 * one the macrobenchmark measures. A separate app, since the connected test wipes it and uninstalls
 * it afterwards, and the only builds that can be told to read a channel as a guest (see
 * MainActivity), which gets the journeys past the login.
 */
val profilingBuildTypes = listOf("nonMinifiedRelease", "benchmarkRelease")

android {
    namespace = "dev.chatter.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.chatter.app"
        minSdk = 33
        // Moving to 37 brings Android 17's behaviour changes, which want trying on a phone rather
        // than following a warning; it gets a change of its own.
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("version")
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"$twitchClientId\"")
        buildConfigField("boolean", "SPONSORING", "$sponsoring")
        // True only in profilingBuildTypes.
        buildConfigField("boolean", "PROFILING", "false")

        // The androidTest microbenchmark (see testBuildType). Emulators are allowed because CI has
        // nothing else; their numbers are only compared with each other, as the Benchmark workflow
        // does.
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    signingConfigs {
        if (uploadKeystore != null) {
            // All four or none; a missing password otherwise fails deep in the signing task without
            // naming the secret.
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
            // People building the app themselves get the link.
            buildConfigField("boolean", "SPONSOR_LINK", "$sponsoring")
            buildConfigField("boolean", "UPDATE_CHECK", "true")
        }
        release {
            buildConfigField("boolean", "SPONSOR_LINK", "$sponsorLink")
            // Play updates its own installs and forbids pointing elsewhere, so only the sideloaded
            // APK checks GitHub.
            buildConfigField("boolean", "UPDATE_CHECK", "$sideloaded")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The upload key if there is one, otherwise the debug key, so release builds stay
            // installable for testing.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
        // What the microbenchmark runs against: the debug build, not debuggable, since debuggable
        // apps run without optimizations. Not minified, so the benchmark can reach the classes R8
        // would rename or remove.
        create("microbenchmark") {
            initWith(getByName("debug"))
            isDebuggable = false
            // A separate app: the connected test uninstalls it afterwards, which must not hit the
            // user's own Chatter.
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    testBuildType = "microbenchmark"
    // AOT-compiles the app when a connected test installs it, like the benchmark plugin does;
    // interpreted code would measure the JIT.
    experimentalProperties["android.experimental.force-aot-compilation"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Keeps the warning count at zero. Warnings that do not apply are suppressed at the spot,
        // with the reason.
        warningsAsErrors = true
        // Dependabot offers new versions (.github/dependabot.yml). As lint warnings they would fail
        // every build the day a release comes out.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
    }

    testOptions {
        unitTests {
            // The repositories log when a provider does not answer, which the tests exercise.
            // android.util.Log throws in plain JVM tests unless stubbed.
            isReturnDefaultValues = true
        }
    }
}

/**
 * The changelog under Settings -> About is the repository's CHANGELOG.md, shipped as the asset
 * changelog.md, so there is no second copy.
 */
abstract class CopyChangelog : DefaultTask() {
    @get:InputFile
    abstract val changelog: RegularFileProperty

    /** Set by the Android variant API, which also makes the build depend on this task. */
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
    // Unit tests only come with the testBuildType, which is the microbenchmark now; they belong to
    // the debug build, and CI runs testDebugUnitTest.
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
    // Lets tests control coroutines and dispatcher clocks.
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.benchmark.junit4)
    // The benchmark runs in the measured app's process and needs an activity and a permission
    // there; only that build gets them. See testBuildType.
    "microbenchmarkImplementation"(libs.androidx.benchmark.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)

    // The profile from :baselineprofile, compiled into the release APK.
    baselineProfile(project(":baselineprofile"))
}
