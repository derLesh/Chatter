package me.lesh.chatter.badges

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import me.lesh.chatter.net.AppJson
import me.lesh.chatter.net.decodeStored
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

/**
 * A kind of badge the user hid in every channel, matched by provider and [Badge.set]. The other
 * badges of the same provider stay.
 */
@Serializable
data class HiddenBadge(
    val provider: BadgeProvider,
    val set: String,
    /** The title of the badge it was hidden from, for the list in the settings. */
    val title: String,
    /** Its picture, for the same list. */
    val url: String? = null,
    /** Its picture for light themes; see [Badge.urlOnLight]. */
    val urlOnLight: String? = null,
) {
    val kind: String get() = Badge.kindOf(provider, set)
}

/** Hidden kinds of badges, kept next to the settings so they move to a new phone with them. */
class HiddenBadgeRepository(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    val hidden: StateFlow<List<HiddenBadge>> = store.data
        .map { p -> decode(p[HIDDEN_BADGES]).orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** [Badge.kind] of every hidden kind, for a lookup per badge while chat lines are drawn. */
    val hiddenKinds: StateFlow<Set<String>> = hidden
        .map { list -> list.mapTo(HashSet()) { it.kind } }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    suspend fun hide(badge: Badge) = change { all ->
        all.filterNot { it.kind == badge.kind } + HiddenBadge(badge.provider, badge.set, badge.title, badge.url, badge.urlOnLight)
    }

    suspend fun show(kind: String) = change { all -> all.filterNot { it.kind == kind } }

    /** Replaces all hidden kinds, for restoring a backup. */
    suspend fun replaceAll(all: List<HiddenBadge>) = store.edit { p -> p[HIDDEN_BADGES] = AppJson.encodeToString(all) }

    private suspend fun change(edit: (List<HiddenBadge>) -> List<HiddenBadge>) = store.edit { p ->
        // Never writes over a list it cannot read; see decodeStored.
        val all = decode(p[HIDDEN_BADGES]) ?: return@edit
        p[HIDDEN_BADGES] = AppJson.encodeToString(edit(all))
    }

    private companion object {
        val HIDDEN_BADGES = stringPreferencesKey("hidden_badges")

        fun decode(raw: String?): List<HiddenBadge>? = decodeStored(raw, emptyList(), "hidden badges")
    }
}
