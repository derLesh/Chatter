package dev.chatter.app.emotes

import android.content.Context
import dev.chatter.app.R
import dev.chatter.app.chat.ChatRepository
import dev.chatter.app.chat.Segment
import dev.chatter.app.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the channels' 7TV emotes up to date via the 7TV EventAPI and reports changes in the chat.
 *
 * To save battery the connection only runs while the app is on screen. After more than
 * [RELOAD_AFTER_MS] away, the 7TV emotes are reloaded once to catch up on missed changes.
 */
class SevenTvLiveUpdates(
    private val context: Context,
    private val client: SevenTvEventClient,
    private val emotes: EmoteRepository,
    private val chat: ChatRepository,
    private val settings: StateFlow<Settings>,
    private val scope: CoroutineScope,
) {
    private var hiddenSince = 0L

    fun start() {
        // Subscribe to whatever sets/users the loaded channels have.
        scope.launch {
            emotes.version.collect { client.setSubscriptions(emotes.sevenTvSubscriptions()) }
        }
        scope.launch {
            combine(chat.uiVisible, settings.map { it.sevenTvEvents }.distinctUntilChanged()) { visible, enabled -> visible && enabled }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (active) {
                        if (hiddenSince != 0L && System.currentTimeMillis() - hiddenSince > RELOAD_AFTER_MS) reloadAll()
                        client.start()
                    } else {
                        client.stop()
                        hiddenSince = System.currentTimeMillis()
                    }
                }
        }
        scope.launch {
            client.events.collect { handle(it) }
        }
    }

    private suspend fun reloadAll() {
        chat.knownRoomIds().forEach { id -> emotes.loadChannel(id, null) }
    }

    private suspend fun handle(event: SevenTvEvent) {
        val actor = event.actor ?: context.getString(R.string.seventv_someone)
        when (event) {
            is SevenTvEvent.EmoteSetUpdate -> {
                val channelId = emotes.channelForSevenTvSet(event.setId) ?: return
                val channel = chat.channelForRoomId(channelId) ?: return
                val added = emotes.applySevenTvUpdate(channelId, event)
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
            is SevenTvEvent.ActiveSetChanged -> {
                val channelId = emotes.channelForSevenTvUser(event.userId) ?: return
                val channel = chat.channelForRoomId(channelId) ?: return
                chat.postNotice(channel, context.getString(R.string.seventv_set_changed, actor))
                emotes.loadChannel(channelId, null) // new set id -> new subscriptions via version
            }
        }
    }

    private companion object {
        const val RELOAD_AFTER_MS = 10 * 60_000L
    }
}
