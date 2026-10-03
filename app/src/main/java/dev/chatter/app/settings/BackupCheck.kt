package dev.chatter.app.settings

import dev.chatter.app.channels.ChannelGroup
import dev.chatter.app.chat.ImageLinks
import java.io.InputStream

/**
 * Limits for what a backup may bring in. Backups get passed around, so they are untrusted input and
 * held to what the settings screen allows: an image host list of just "com" would load every linked
 * picture and reveal the user's IP address to whoever posted it.
 */
object BackupCheck {
    /** The font size slider's range. */
    val FONT_SIZES = 10f..24f

    /** The message limit slider's range. */
    val MESSAGE_LIMITS = 100..2000

    const val MAX_HOSTS = 50
    const val MAX_KEYWORDS = 200
    const val MAX_RECENT_EMOTES = 50
    const val MAX_RULES = 200
    const val MAX_PATTERN = 500
    const val MAX_NICKNAMES = 2000
    const val MAX_NICKNAME = 50
    const val MAX_CHANNELS = 200

    /**
     * Well above a backup at all limits. Reading a large file into memory just to reject it could
     * run out of memory.
     */
    const val MAX_FILE_BYTES = 2 * 1024 * 1024

    /** The text of a backup file, or null if it is too large to be one. */
    fun readLimited(input: InputStream): String? {
        val bytes = input.readNBytes(MAX_FILE_BYTES + 1)
        return if (bytes.size > MAX_FILE_BYTES) null else bytes.decodeToString()
    }

    private val LOGIN = Regex("^[a-z0-9_]{1,25}$")
    private val HOST_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    /** [backup] with everything out of bounds dropped or clamped. */
    fun clean(backup: SettingsBackup): SettingsBackup = backup.copy(
        settings = backup.settings?.let(::clean),
        rules = backup.rules
            ?.filter { it.pattern.isNotBlank() && it.pattern.length <= MAX_PATTERN }
            ?.distinctBy { it.id }
            ?.take(MAX_RULES),
        nicknames = backup.nicknames
            ?.mapKeys { it.key.trim().lowercase() }
            ?.mapValues { it.value.trim().take(MAX_NICKNAME) }
            ?.filter { (login, name) -> LOGIN.matches(login) && name.isNotEmpty() }
            ?.entries?.take(MAX_NICKNAMES)?.associate { it.key to it.value },
        channels = backup.channels?.let { c ->
            // Group ids end up in the comma-separated page list; see ChannelGroup.isValidId.
            // Invalid ones are dropped together with their place in the list.
            val groups = c.groups.filter { ChannelGroup.isValidId(it.id) }
            val keys = groups.map { it.key }.toSet()
            c.copy(
                logins = c.logins.filter { !ChannelGroup.isKey(it) || it in keys }.take(MAX_CHANNELS),
                groups = groups,
            )
        },
    )

    private fun clean(s: Settings) = s.copy(
        fontSize = s.fontSize.takeIf { !it.isNaN() }?.coerceIn(FONT_SIZES) ?: Settings().fontSize,
        messageLimit = s.messageLimit.coerceIn(MESSAGE_LIMITS),
        mentionKeywords = keywords(s.mentionKeywords),
        muteKeywords = keywords(s.muteKeywords),
        // Stored space-separated.
        recentEmotes = s.recentEmotes.filter { it.isNotBlank() && ' ' !in it }.distinct().take(MAX_RECENT_EMOTES),
        imageHosts = hosts(s.imageHosts),
    )

    /** Stored comma-separated. */
    private fun keywords(list: List<String>) =
        list.map { it.replace(",", "").trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_KEYWORDS)

    /**
     * Hosts as the settings screen takes them ([ImageLinks.cleanHost]), and only real ones with a
     * dot. A bare top-level domain would match every site under it.
     */
    fun hosts(list: List<String>): List<String> = list
        .map(ImageLinks::cleanHost)
        .filter { host -> '.' in host && host.split('.').all { HOST_LABEL.matches(it) } }
        .distinct()
        .take(MAX_HOSTS)
}
