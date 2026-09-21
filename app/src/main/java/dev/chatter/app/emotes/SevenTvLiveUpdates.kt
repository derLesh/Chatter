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
 * Keeps the channels' 7TV emotes and badges up to date via the 7TV EventAPI, and reports the emote
 * changes in the chat.
 *
 * This runs for every joined channel, not just the one on screen. Whether the changes are also
 * announced in the chat is a display choice and does not affect keeping the emotes current.
 *
 * To save battery the connection only runs while the app is on screen; coming back reloads the
 * emotes once to catch up on what was missed and on whatever failed to load earlier.
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
        // Subscribe to whatever sets/users the loaded channels have.
        scope.launch {
            emotes.version.collect { client.setSubscriptions(subscriptions()) }
        }
        scope.launch {
            chat.windows.anyVisible.collectLatest { active ->
                if (active) {
                    client.start()
                    // Catch up on anything missed while the socket was down, and on channels whose
                    // first load failed: without this their set is never subscribed to at all.
                    reloadAll()
                } else {
                    client.stop()
                }
            }
        }
        scope.launch {
            client.events.collect { handle(it) }
        }
    }

    /**
     * The emote sets of every channel, plus its cosmetics.
     *
     * Badges are the one thing 7TV no longer hands out as a list: since the cosmetics endpoint
     * was retired they are pushed per channel, a description of the badge and the people wearing
     * it arriving separately. Chatterino listens the same way.
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
        // The global emotes are loaded once at login and stay for the session, so a load that
        // failed back then would never be tried again; this is the one place that comes back to
        // it. It returns right away once they are there.
        emotes.loadGlobal()
        chat.rooms.knownIds().forEach { id -> emotes.refreshChannel(id) }
    }

    private suspend fun handle(event: SevenTvEvent) {
        val actor = event.actor ?: context.getString(R.string.seventv_someone)
        when (event) {
            is SevenTvEvent.EmoteSetUpdate -> {
                val channelId = emotes.channelForSevenTvSet(event.setId) ?: return
                // Take the emotes over first: they must land even when no channel name can be
                // resolved to write a notice into, which would otherwise drop the change entirely.
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
            is SevenTvEvent.EntitlementChanged ->
                badges.sevenTvWearer(event.twitchUserId, event.refId, event.worn)
            is SevenTvEvent.ActiveSetChanged -> {
                val channelId = emotes.channelForSevenTvUser(event.userId) ?: return
                emotes.loadChannel(channelId, null) // new set id -> new subscriptions via version
                val channel = chat.rooms.channelOf(channelId)?.takeIf { settings.value.sevenTvEvents } ?: return
                chat.postNotice(channel, context.getString(R.string.seventv_set_changed, actor))
            }
        }
    }
}
