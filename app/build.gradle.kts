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
 * Whether this build may show the way to GitHub Sponsors.
 *
 * Google Play wants payments that happen in an app to go through its own billing, and a link
 * straight past it is the kind of thing a review takes issue with — so the Play build does not
 * carry one, and the APK people install themselves does. The default is the careful one: pass
 * `-Pdistribution=github` for the sideload APK, and forgetting it can only ever leave the link
 * out, never put it where it must not be.
 */
val sponsorLink = sponsoring && (project.findProperty("distribution") as String?) == "github"

android {
    namespace = "dev.chatter.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.chatter.app"
        minSdk = 33
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("version")
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"$twitchClientId\"")
        buildConfigField("boolean", "SPONSORING", "$sponsoring")
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
        }
        release {
            buildConfigField("boolean", "SPONSOR_LINK", "$sponsorLink")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The upload key when there is one, otherwise the debug key, so a release build can
            // still be installed directly for testing.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

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
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyChangelog, CopyChangelog::assets)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
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
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // Lets the tests drive the coroutines and the clock of anything built around a dispatcher.
    testImplementation(libs.kotlinx.coroutines.test)

    // The profile the :baselineprofile module generates, compiled into the release APK.
    baselineProfile(project(":baselineprofile"))
}
