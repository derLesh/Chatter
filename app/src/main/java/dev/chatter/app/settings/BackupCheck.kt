package dev.chatter.app.settings

import dev.chatter.app.chat.ImageLinks

/**
 * What a backup is allowed to bring in.
 *
 * A backup is a file people pass around — "here are my filters" — so it is input from somebody
 * else, not a copy of what this app once wrote. Everything in it is held to what the settings
 * screen itself would allow before any of it is written: an image host list of just "com" would
 * otherwise have the app fetch every picture anybody links, and hand the user's address to whoever
 * posted it.
 */
object BackupCheck {
    /** The range the font size slider offers. */
    val FONT_SIZES = 10f..24f

    /** The range the message limit slider offers. */
    val MESSAGE_LIMITS = 100..2000

    const val MAX_HOSTS = 50
    const val MAX_KEYWORDS = 200
    const val MAX_RECENT_EMOTES = 50
    const val MAX_RULES = 200
    const val MAX_PATTERN = 500
    const val MAX_NICKNAMES = 2000
    const val MAX_NICKNAME = 50
    const val MAX_CHANNELS = 200

    private val LOGIN = Regex("^[a-z0-9_]{1,25}$")
    private val HOST_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    /** [backup] with everything out of bounds dropped or brought back into bounds. */
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
        channels = backup.channels?.let { it.copy(logins = it.logins.take(MAX_CHANNELS)) },
    )

    private fun clean(s: Settings) = s.copy(
        fontSize = s.fontSize.takeIf { !it.isNaN() }?.coerceIn(FONT_SIZES) ?: Settings().fontSize,
        messageLimit = s.messageLimit.coerceIn(MESSAGE_LIMITS),
        mentionKeywords = keywords(s.mentionKeywords),
        muteKeywords = keywords(s.muteKeywords),
        // Stored space-separated; a name with a space in it would come back as two.
        recentEmotes = s.recentEmotes.filter { it.isNotBlank() && ' ' !in it }.distinct().take(MAX_RECENT_EMOTES),
        imageHosts = hosts(s.imageHosts),
    )

    /** Stored comma-separated, so a comma inside one would split it in two on the next start. */
    private fun keywords(list: List<String>) =
        list.map { it.replace(",", "").trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_KEYWORDS)

    /**
     * The hosts the way the settings screen takes them ([ImageLinks.cleanHost]), and only real
     * ones: a name with a dot in it, made of what a host name may hold. A bare top-level domain
     * would match every site under it.
     */
    fun hosts(list: List<String>): List<String> = list
        .map(ImageLinks::cleanHost)
        .filter { host -> '.' in host && host.split('.').all { HOST_LABEL.matches(it) } }
        .distinct()
        .take(MAX_HOSTS)
}
