package dev.chatter.app.update

import dev.chatter.app.changelog.ChangelogParser
import dev.chatter.app.changelog.Release
import dev.chatter.app.changelog.Version
import dev.chatter.app.net.AppJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A GitHub release, as far as the update card needs it. Stored between checks. */
@Serializable
data class AvailableUpdate(
    val version: String,
    /** Publishing day, "2026-09-22", or empty. */
    val date: String,
    /** The release's CHANGELOG.md entries, as published by the release workflow. */
    val notes: String,
    /** The APK, or the release page if there is none. */
    val url: String,
) {
    /**
     * The notes as a [Release]. GitHub's text lacks the heading the parser expects, so it is added.
     */
    fun release(): Release? = ChangelogParser.parse("## $version — $date\n$notes").firstOrNull()
}

/** Parsing and timing of the update check. */
object UpdateCheck {
    /** Drafts and pre-releases are never "latest". */
    const val LATEST_URL = "https://api.github.com/repos/derLesh/Chatter/releases/latest"

    /** GitHub is asked at most this often. */
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

    /** The latest release from GitHub's answer, or null if it names no version. */
    fun parse(json: String): AvailableUpdate? {
        val release = runCatching { AppJson.decodeFromString<GitHubRelease>(json) }.getOrNull() ?: return null
        // Tags look like "v0.5.0".
        val version = Version.parse(release.tag.removePrefix("v")) ?: return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }?.url?.takeIf(::isGitHub)
        return AvailableUpdate(
            version = version.toString(),
            date = release.publishedAt?.take(10).orEmpty(),
            notes = release.body.orEmpty(),
            url = apk ?: release.page.takeIf(::isGitHub) ?: return null,
        )
    }

    /**
     * Whether [url] is https on GitHub. The browser downloads it and offers to install it, so
     * whatever answers in GitHub's place (captive portal, proxy) must not choose it.
     */
    internal fun isGitHub(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val host = parsed.host
        return parsed.isHttps && parsed.username.isEmpty() &&
            GITHUB_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** Release pages are on github.com, their files on githubusercontent.com. */
    private val GITHUB_HOSTS = listOf("github.com", "githubusercontent.com")

    /** Whether [update] is worth telling somebody running [installed] about. */
    fun isNewer(update: AvailableUpdate, installed: Version?): Boolean {
        val latest = Version.parse(update.version) ?: return false
        return installed != null && latest > installed
    }

    /**
     * Whether to ask again. A clock that went backwards counts as due, or it would never be due
     * again.
     */
    fun isDue(lastChecked: Long?, now: Long): Boolean =
        lastChecked == null || now < lastChecked || now - lastChecked >= INTERVAL_MS
}
