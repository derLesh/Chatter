package me.lesh.chatter.upload

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.lesh.chatter.net.AppJson
import java.io.File

/** The user's own upload sites and the keys given to the preset ones, by [PresetUploader.id]. */
@Serializable
data class UploaderSetup(
    val custom: List<CustomUploader> = emptyList(),
    val keys: Map<String, String> = emptyMap(),
) {
    /** Every site a picture can go to now, the presets first. */
    val targets: List<UploadTarget>
        get() = PresetUploader.entries.mapNotNull { UploadTarget.of(it, keys[it.id]) } + custom.mapNotNull(UploadTarget::of)

    fun target(id: String): UploadTarget? = targets.firstOrNull { it.id == id }
}

/**
 * Where [UploaderSetup] is kept: a file of its own next to the list of uploads, not the settings.
 * Keys and the user's own sites with their headers can open an account, so they stay on this
 * phone, outside the settings backup and the transfer to a new one.
 */
class UploaderStore(private val file: File, scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val _setup = MutableStateFlow(UploaderSetup())
    val setup: StateFlow<UploaderSetup> = _setup.asStateFlow()
    private val lock = Mutex()
    private var loaded = false

    init {
        scope.launch(io) { lock.withLock { loadOnce() } }
    }

    suspend fun update(change: (UploaderSetup) -> UploaderSetup) = withContext(io) {
        lock.withLock {
            loadOnce()
            val next = change(_setup.value)
            _setup.value = next
            file.parentFile?.mkdirs()
            file.writeText(AppJson.encodeToString(next))
        }
    }

    private fun loadOnce() {
        if (loaded) return
        loaded = true
        _setup.value = runCatching { AppJson.decodeFromString<UploaderSetup>(file.readText()) }.getOrDefault(UploaderSetup())
    }
}
