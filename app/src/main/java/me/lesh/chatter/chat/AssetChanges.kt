package me.lesh.chatter.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Keeps every scope's latest arrival even when several providers finish before chat can collect. */
internal class AssetChanges {
    private val versions = MutableStateFlow<Map<String?, Long>>(emptyMap())
    val arrived: StateFlow<Map<String?, Long>> = versions

    fun changed(channelId: String? = null) {
        versions.update { it + (channelId to ((it[channelId] ?: 0L) + 1)) }
    }

    fun forget(channelId: String) {
        versions.update { it - channelId }
    }

    fun revision(channelId: String?): Long = versions.value.let {
        (it[null] ?: 0L) + (if (channelId == null) 0L else it[channelId] ?: 0L)
    }
}
