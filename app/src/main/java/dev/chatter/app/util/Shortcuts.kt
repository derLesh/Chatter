package dev.chatter.app.util

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.chatter.app.MainActivity
import dev.chatter.app.R
import dev.chatter.app.channels.ChannelIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Carried by a launch intent that should open the inbox rather than a channel. */
const val EXTRA_INBOX = "open_inbox"

/** Carried by a launch intent that names the channel to open. */
const val EXTRA_CHANNEL = "channel"

private const val CHANNEL_PREFIX = "channel:"
private const val INBOX_ID = "inbox"

/** Marks a shortcut as a conversation, which is what lets Android bubble it and share into it. */
private const val CONVERSATION_CATEGORY = "android.shortcut.conversation"

fun channelShortcutId(channel: String): String = "$CHANNEL_PREFIX$channel"

/**
 * Opens the app, on [channel] when one is given. It goes through the launcher entry rather than
 * straight to MainActivity: the app icon is an activity-alias (see [AppIcon]), so a running task
 * has that alias as its root. An intent naming MainActivity does not match it, and Android then
 * only raises the task without ever delivering the intent — the tap would do nothing.
 */
fun appLaunchIntent(context: Context, channel: String?): Intent =
    (context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_CHANNEL, channel)

/**
 * One channel as Android knows it. The same shortcut does three jobs, which is why there is only
 * this one description of it: the launcher offers it when the icon is held, the system shows it
 * as a person in the share sheet, and a mention notification hangs off it — the last one is what
 * makes the notification a conversation Android is willing to float as a bubble.
 */
fun channelShortcut(
    context: Context,
    channel: String,
    name: String,
    icon: IconCompat,
    rank: Int = 0,
): ShortcutInfoCompat = ShortcutInfoCompat.Builder(context, channelShortcutId(channel))
    .setShortLabel(name)
    .setLongLabel(name)
    .setLongLived(true)
    .setRank(rank)
    .setIcon(icon)
    .setCategories(setOf(CONVERSATION_CATEGORY))
    .setPerson(Person.Builder().setName(name).setKey(channelShortcutId(channel)).setIcon(icon).setImportant(true).build())
    .setIntent(appLaunchIntent(context, channel))
    .build()

/**
 * Keeps the shortcuts behind the app icon in step with the channel list, so holding the icon
 * jumps straight into a channel or into the inbox instead of opening wherever the app was left.
 */
class ChannelShortcuts(
    private val context: Context,
    private val identities: Flow<List<ChannelIdentity>>,
    private val icons: ChannelIcons,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch { identities.collect { publish(it) } }
    }

    private suspend fun publish(channels: List<ChannelIdentity>) {
        // Android takes only so many, and one of the slots belongs to the inbox.
        val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - 1).coerceAtLeast(1)
        val shortcuts = channels.take(room).mapIndexed { index, it ->
            channelShortcut(context, it.login, it.name, icons.channel(it.login), rank = index)
        }
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts + inboxShortcut(channels.size))
            removeStale(channels.map { it.login }.toSet())
        }
    }

    /** The inbox is only worth a slot once there are channels that could fill it. */
    private fun inboxShortcut(channelCount: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, INBOX_ID)
            .setShortLabel(context.getString(R.string.shortcut_inbox))
            .setLongLabel(context.getString(R.string.shortcut_inbox))
            .setRank(channelCount)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_inbox))
            .setIntent(appLaunchIntent(context, channel = null).putExtra(EXTRA_INBOX, true))
            .build()

    /**
     * A channel the user removed would otherwise keep haunting the share sheet and the people
     * space: long-lived shortcuts stay cached there after they leave the dynamic list.
     */
    private fun removeStale(logins: Set<String>) {
        val matchAll = ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED
        val stale = ShortcutManagerCompat.getShortcuts(context, matchAll)
            .map { it.id }
            .filter { it.startsWith(CHANNEL_PREFIX) && it.removePrefix(CHANNEL_PREFIX) !in logins }
        if (stale.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, stale)
    }
}
