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

/** In a launch intent: open the inbox on this tab instead of a channel. */
const val EXTRA_INBOX_TAB = "inbox_tab"

const val INBOX_TAB_MENTIONS = 0
const val INBOX_TAB_WHISPERS = 1

/** In a whisper notification's reply action: who the answer goes to. */
const val EXTRA_WHISPER = "whisper"
const val EXTRA_WHISPER_USER_ID = "whisper_user_id"

/**
 * In every reply action: the user id of the account the notification was for. The answer is sent as
 * that account or not at all, never as whichever account is active by then.
 */
const val EXTRA_ACCOUNT = "account"

/** In a launch intent: the channel to open. */
const val EXTRA_CHANNEL = "channel"

/**
 * A channel from a live notification. Unlike [EXTRA_CHANNEL] it may add the channel to the list,
 * but only one Chatter notified about; see LiveAlerts.
 */
const val EXTRA_LIVE_CHANNEL = "live_channel"

/**
 * The channel the baseline profile and the macrobenchmark read as a guest to get past the login.
 * Only the profiling builds listen; see `profilingBuildTypes` in build.gradle.kts. The benchmark
 * module repeats the name, it cannot see this constant.
 */
const val EXTRA_PROFILING_CHANNEL = "profiling_channel"

private const val CHANNEL_PREFIX = "channel:"
private const val INBOX_ID = "inbox"

/** Marks a conversation shortcut, which Android needs for bubbles and the share sheet. */
private const val CONVERSATION_CATEGORY = "android.shortcut.conversation"

fun channelShortcutId(channel: String): String = "$CHANNEL_PREFIX$channel"

/**
 * Opens the app, on [channel] if given. Goes through the launcher alias instead of MainActivity:
 * the running task's root is the alias (see [AppIcon]), and an intent naming MainActivity would
 * only bring the task to front without being delivered.
 */
fun appLaunchIntent(context: Context, channel: String?): Intent =
    (context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_CHANNEL, channel)

/**
 * A channel as an Android shortcut. Used by the launcher, the share sheet and mention
 * notifications; the last makes a notification a conversation Android can show as a bubble.
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

/** Keeps the app icon's shortcuts in sync with the channel list. */
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
        // Android allows only a few; one slot is the inbox's.
        val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - 1).coerceAtLeast(1)
        val shortcuts = channels.take(room).mapIndexed { index, it ->
            channelShortcut(context, it.login, it.name, icons.channel(it.login), rank = index)
        }
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts + inboxShortcut(channels.size))
            removeStale(channels.map { it.login }.toSet())
        }
    }

    /** Only once there are channels. */
    private fun inboxShortcut(channelCount: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, INBOX_ID)
            .setShortLabel(context.getString(R.string.shortcut_inbox))
            .setLongLabel(context.getString(R.string.shortcut_inbox))
            .setRank(channelCount)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_inbox))
            .setIntent(appLaunchIntent(context, channel = null).putExtra(EXTRA_INBOX_TAB, INBOX_TAB_MENTIONS))
            .build()

    /**
     * Long-lived shortcuts stay cached in the share sheet after leaving the dynamic list, so
     * removed channels are cleaned out explicitly.
     */
    private fun removeStale(logins: Set<String>) {
        val matchAll = ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED
        val stale = ShortcutManagerCompat.getShortcuts(context, matchAll)
            .map { it.id }
            .filter { it.startsWith(CHANNEL_PREFIX) && it.removePrefix(CHANNEL_PREFIX) !in logins }
        if (stale.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, stale)
    }
}
