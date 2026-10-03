package dev.chatter.app.settings

import dev.chatter.app.channels.ChannelGroup
import dev.chatter.app.channels.ChannelRepository
import dev.chatter.app.chat.ChatRule
import dev.chatter.app.chat.NicknameRepository
import dev.chatter.app.chat.RuleRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * All user settings in one file, Chatter's own format, for moving to a new phone or keeping a copy.
 * Every part is optional; an older or hand-written backup restores what it has.
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

        // Readable, users may edit backups by hand.
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

@Serializable
data class ChannelBackup(
    /**
     * Channels in the user's order, with combined chats as their key ("+" and id) in their place.
     * Versions without combined chats drop those keys as invalid logins.
     */
    val logins: List<String> = emptyList(),
    /** Names the user gave channels, by login. */
    val names: Map<String, String> = emptyMap(),
    val notificationsOff: List<String> = emptyList(),
    val hiddenUnread: List<String> = emptyList(),
    val groups: List<ChannelGroup> = emptyList(),
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
                logins = channels.pages.value,
                names = channels.customNames.value,
                notificationsOff = channels.mutedChannels.value.toList(),
                hiddenUnread = channels.hiddenUnread.value.toList(),
                groups = channels.groups.value.values.toList(),
            ),
        )
    )

    /**
     * The backup in [text] after [BackupCheck], or null if it is not a Chatter backup. Nothing is
     * written; [apply] does that once the user has seen the changes.
     */
    fun read(text: String): SettingsBackup? {
        val backup = runCatching { SettingsBackup.json.decodeFromString<SettingsBackup>(text) }.getOrNull() ?: return null
        if (backup.app != SettingsBackup.APP) return null
        return BackupCheck.clean(backup)
    }

    /** Applies [backup] as [read] returned it. */
    suspend fun apply(backup: SettingsBackup) {
        backup.settings?.let { settings.replaceAll(it) }
        backup.rules?.let { rules.replaceAll(it) }
        backup.nicknames?.let { nicknames.replaceAll(it) }
        backup.channels?.let {
            channels.restore(it.logins, it.names, it.notificationsOff.toSet(), it.hiddenUnread.toSet(), it.groups)
        }
    }
}
