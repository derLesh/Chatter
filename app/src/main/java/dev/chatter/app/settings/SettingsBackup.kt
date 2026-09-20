package dev.chatter.app.settings

import dev.chatter.app.channels.ChannelRepository
import dev.chatter.app.chat.ChatRule
import dev.chatter.app.chat.NicknameRepository
import dev.chatter.app.chat.RuleRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the app knows about how the user likes it, in one file.
 *
 * This is Chatter's own format and nobody else's: it is meant for moving to a new phone or
 * keeping a copy before experimenting, not for importing another client's settings. Every part
 * is optional, so an older backup — or one written by hand — restores what it does carry and
 * leaves the rest alone.
 */
@Serializable
data class SettingsBackup(
    val app: String = APP,
    val version: Int = VERSION,
    val createdAt: Long = 0,
    val settings: Settings? = null,
    val rules: List<ChatRule>? = null,
    /** Nickname by lowercase login. */
    val nicknames: Map<String, String>? = null,
    val channels: ChannelBackup? = null,
) {
    companion object {
        const val APP = "Chatter"
        const val VERSION = 1

        // Readable on purpose: a backup is a file the user may well open and edit themselves.
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

@Serializable
data class ChannelBackup(
    val logins: List<String> = emptyList(),
    /** Names the user gave channels, by login. */
    val names: Map<String, String> = emptyMap(),
    val notificationsOff: List<String> = emptyList(),
    val hiddenUnread: List<String> = emptyList(),
)

/** Writes and reads [SettingsBackup] files. */
class BackupManager(
    private val settings: SettingsRepository,
    private val rules: RuleRepository,
    private val nicknames: NicknameRepository,
    private val channels: ChannelRepository,
) {
    fun export(): String = SettingsBackup.json.encodeToString(
        SettingsBackup(
            createdAt = System.currentTimeMillis(),
            settings = settings.settings.value,
            rules = rules.rules.value,
            nicknames = nicknames.nicknames.value,
            channels = ChannelBackup(
                logins = channels.channels.value,
                names = channels.customNames.value,
                notificationsOff = channels.mutedChannels.value.toList(),
                hiddenUnread = channels.hiddenUnread.value.toList(),
            ),
        )
    )

    /** Returns false if the text is not a Chatter backup at all; anything it carries is applied. */
    suspend fun import(text: String): Boolean {
        val backup = runCatching { SettingsBackup.json.decodeFromString<SettingsBackup>(text) }.getOrNull() ?: return false
        if (backup.app != SettingsBackup.APP) return false
        backup.settings?.let { settings.replaceAll(it) }
        backup.rules?.let { rules.replaceAll(it) }
        backup.nicknames?.let { nicknames.replaceAll(it) }
        backup.channels?.let {
            channels.restore(it.logins, it.names, it.notificationsOff.toSet(), it.hiddenUnread.toSet())
        }
        return true
    }
}
