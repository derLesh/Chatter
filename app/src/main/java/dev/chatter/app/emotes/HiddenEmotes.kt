package dev.chatter.app.emotes

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.net.AppJson
import dev.chatter.app.net.decodeStored
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

/** An emote the user hid; it shows as its name instead. Matched by provider and id, not by name. */
@Serializable
data class HiddenEmote(
    val provider: EmoteProvider,
    val id: String,
    val name: String,
    /** Its picture, for the list in the settings. */
    val url: String? = null,
    /** Login of the one channel it is hidden in; null hides it everywhere. */
    val channel: String? = null,
) {
    val key: String get() = keyOf(provider, id)

    companion object {
        fun keyOf(provider: EmoteProvider, id: String) = "${provider.name}:$id"
    }
}

/** The hidden emotes, set up for a lookup per emote while chat lines are drawn. */
data class HiddenEmotes(val list: List<HiddenEmote> = emptyList()) {
    private val everywhere: Set<String> = list.filter { it.channel == null }.mapTo(HashSet()) { it.key }
    private val byChannel: Map<String, Set<String>> =
        list.filter { it.channel != null }.groupBy({ it.channel!! }, { it.key }).mapValues { it.value.toSet() }

    fun isEmpty() = list.isEmpty()

    /** Whether [emote] is hidden in [channel], the login of the chat it is shown in. */
    fun hides(emote: Emote, channel: String?): Boolean {
        if (list.isEmpty()) return false
        val key = HiddenEmote.keyOf(emote.provider, emote.id)
        return key in everywhere || (channel != null && byChannel[channel]?.contains(key) == true)
    }

    companion object {
        val NONE = HiddenEmotes()
    }
}

/** Hidden emotes, kept next to the settings so they move to a new phone with them. */
class HiddenEmoteRepository(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    val hidden: StateFlow<HiddenEmotes> = store.data
        .map { p -> HiddenEmotes(decode(p[HIDDEN_EMOTES]).orEmpty()) }
        .stateIn(scope, SharingStarted.Eagerly, HiddenEmotes.NONE)

    /** Hides [emote] in [channel], or everywhere for null. Replaces an earlier choice for it. */
    suspend fun hide(emote: Emote, channel: String?) = change { all ->
        all.filterNot { it.key == HiddenEmote.keyOf(emote.provider, emote.id) } +
            HiddenEmote(emote.provider, emote.id, emote.name, emote.url, channel?.lowercase())
    }

    suspend fun show(hidden: HiddenEmote) = change { all -> all - hidden }

    /** Replaces all hidden emotes, for restoring a backup. */
    suspend fun replaceAll(all: List<HiddenEmote>) = store.edit { p -> p[HIDDEN_EMOTES] = AppJson.encodeToString(all) }

    private suspend fun change(edit: (List<HiddenEmote>) -> List<HiddenEmote>) = store.edit { p ->
        // Never writes over a list it cannot read; see decodeStored.
        val all = decode(p[HIDDEN_EMOTES]) ?: return@edit
        p[HIDDEN_EMOTES] = AppJson.encodeToString(edit(all))
    }

    private companion object {
        val HIDDEN_EMOTES = stringPreferencesKey("hidden_emotes")

        fun decode(raw: String?): List<HiddenEmote>? = decodeStored(raw, emptyList(), "hidden emotes")
    }
}
