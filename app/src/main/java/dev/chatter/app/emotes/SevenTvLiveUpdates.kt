package dev.chatter.app.emotes

import android.content.Context
import dev.chatter.app.R
import dev.chatter.app.badges.BadgeRepository
import dev.chatter.app.chat.ChatRepository
import dev.chatter.app.chat.Segment
import dev.chatter.app.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the 7TV emotes, badges and paints of every joined channel current via the EventAPI and
 * reports emote changes in the chat (if enabled).
 *
 * To save battery the connection only runs while the app is on screen; coming back reloads the
 * emotes once.
 */
class SevenTvLiveUpdates(
    private val context: Context,
    private val client: SevenTvEventClient,
    private val emotes: EmoteRepository,
    private val badges: BadgeRepository,
    private val chat: ChatRepository,
    private val settings: StateFlow<Settings>,
    private val scope: CoroutineScope,
) {
    fun start() {
        // Subscribe to the sets and users of the loaded channels.
        scope.launch {
            emotes.version.collect { client.setSubscriptions(subscriptions()) }
        }
        scope.launch {
            chat.windows.anyVisible.collectLatest { active ->
                if (active) {
                    client.start()
                    // Catches up on changes missed while away, and on channels whose first load
                    // failed and so were never subscribed.
                    reloadAll()
                } else {
                    client.stop()
                }
            }
        }
        scope.launch {
            client.events.collect { handle(it) }
        }
        // A connection lost while the app was open missed the changes in between.
        scope.launch {
            client.reconnected.collect { reloadAll() }
        }
    }

    /**
     * The emote sets of every channel plus its cosmetics. 7TV badges are only pushed per channel
     * since the cosmetics endpoint was retired; Chatterino does the same.
     */
    private fun subscriptions(): Set<SevenTvSubscription> =
        emotes.sevenTvSubscriptions() + chat.rooms.knownIds().flatMap { roomId ->
            listOf(
                SevenTvSubscription.ofChannel("cosmetic.create", roomId),
                SevenTvSubscription.ofChannel("entitlement.create", roomId),
                SevenTvSubscription.ofChannel("entitlement.delete", roomId),
            )
        }

    private suspend fun reloadAll() {
        // Global emotes load once at login; this is the only retry if that failed. Returns at once
        // if they are loaded.
        emotes.loadGlobal()
        chat.rooms.knownIds().forEach { id -> emotes.refreshChannel(id) }
    }

    private suspend fun handle(event: SevenTvEvent) {
        val actor = event.actor ?: context.getString(R.string.seventv_someone)
        when (event) {
            is SevenTvEvent.EmoteSetUpdate -> {
                val channelId = emotes.channelForSevenTvSet(event.setId) ?: return
                // Applied first, so the emotes change even if no channel name is known for the
                // notice.
                val added = emotes.applySevenTvUpdate(channelId, event)
                val channel = chat.rooms.channelOf(channelId)?.takeIf { settings.value.sevenTvEvents } ?: return
                if (added.isNotEmpty()) {
                    chat.postNotice(
                        channel,
                        context.getString(R.string.seventv_added, actor),
                        added.flatMap { listOf(Segment.EmoteSeg(it), Segment.Text(" ${it.name} ")) },
                    )
                }
                event.removed.forEach { chat.postNotice(channel, context.getString(R.string.seventv_removed, actor, it.name)) }
                event.renamed.forEach { (old, new) ->
                    chat.postNotice(channel, context.getString(R.string.seventv_renamed, actor, old.name), listOf(Segment.Text(new.name)))
                }
            }
            is SevenTvEvent.BadgeCreated -> badges.sevenTvBadge(event.id, event.name, event.tooltip)
            is SevenTvEvent.PaintCreated -> badges.sevenTvPaint(event.paint)
            is SevenTvEvent.EntitlementChanged -> when (event.cosmetic) {
                SevenTvEvent.Cosmetic.Badge -> badges.sevenTvWearer(event.twitchUserId, event.refId, event.worn)
                SevenTvEvent.Cosmetic.Paint -> badges.sevenTvPaintWearer(event.twitchUserId, event.refId, event.worn)
            }
            is SevenTvEvent.ActiveSetChanged -> {
                val channelId = emotes.channelForSevenTvUser(event.userId) ?: return
                emotes.loadChannel(channelId, null) // new set id leads to new subscriptions via version
                val channel = chat.rooms.channelOf(channelId)?.takeIf { settings.value.sevenTvEvents } ?: return
                chat.postNotice(channel, context.getString(R.string.seventv_set_changed, actor))
            }
        }
    }
}
