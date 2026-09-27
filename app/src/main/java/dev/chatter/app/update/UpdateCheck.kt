package dev.chatter.app.update

import dev.chatter.app.changelog.ChangelogParser
import dev.chatter.app.changelog.Release
import dev.chatter.app.changelog.Version
import dev.chatter.app.net.AppJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A release on GitHub, as much of it as telling the user about it takes. It is kept on the device
 * between checks, so the news is still there after a restart without asking GitHub again.
 */
@Serializable
data class AvailableUpdate(
    val version: String,
    /** The day it was published, "2026-09-22", or empty when GitHub did not say. */
    val date: String,
    /** The release's entries of CHANGELOG.md, which is what the release workflow publishes. */
    val notes: String,
    /** The APK itself, or the release page when the release carries none. */
    val url: String,
) {
    /**
     * The notes the way the changelog page shows a release. GitHub's copy is only the entries,
     * without the heading the parser opens a release on, so the heading is put back first.
     */
    fun release(): Release? = ChangelogParser.parse("## $version — $date\n$notes").firstOrNull()
}

/** What finding out about a new release involves, apart from the asking itself. */
object UpdateCheck {
    /** Drafts and pre-releases are not "latest", so this is only ever something to install. */
    const val LATEST_URL = "https://api.github.com/repos/derLesh/Chatter/releases/latest"

    /** How long a check counts for; GitHub is asked at most this often. */
    const val INTERVAL_MS = 24 * 60 * 60 * 1000L

    @Serializable
    private class GitHubRelease(
        @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val page: String,
        @SerialName("published_at") val publishedAt: String? = null,
        val body: String? = null,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
    )

    /** GitHub's answer for the latest release, or null when it does not name a version. */
    fun parse(json: String): AvailableUpdate? {
        val release = runCatching { AppJson.decodeFromString<GitHubRelease>(json) }.getOrNull() ?: return null
        // The release workflow tags "v0.5.0"; the version is what follows the v.
        val version = Version.parse(release.tag.removePrefix("v")) ?: return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }?.url
        return AvailableUpdate(
            version = version.toString(),
            date = release.publishedAt?.take(10).orEmpty(),
            notes = release.body.orEmpty(),
            url = apk ?: release.page,
        )
    }

    /** Whether [update] is worth telling somebody running [installed] about. */
    fun isNewer(update: AvailableUpdate, installed: Version?): Boolean {
        val latest = Version.parse(update.version) ?: return false
        return installed != null && latest > installed
    }

    /**
     * Whether it is time to ask again. A clock that went backwards since the last check would
     * otherwise keep it from ever being due; that counts as due as well.
     */
    fun isDue(lastChecked: Long?, now: Long): Boolean =
        lastChecked == null || now < lastChecked || now - lastChecked >= INTERVAL_MS
}
