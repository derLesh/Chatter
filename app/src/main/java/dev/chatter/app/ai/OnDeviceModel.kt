package dev.chatter.app.ai

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import dev.chatter.app.chat.ChatItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Gemini Nano can do on this phone right now. */
enum class ModelState { Unknown, Unsupported, Downloadable, Downloading, Ready }

/** Why a summary did not come. [Refused]: AICore's safety check turned the chat down. */
enum class ModelTrouble { Busy, Quota, Unsupported, Refused, Other }

/** What the model has written so far; [leftOut] are the messages the safety check refused. */
data class Summary(val text: String, val leftOut: List<ChatItem> = emptyList())

class ModelFailure(val trouble: ModelTrouble, cause: Throwable? = null) : Exception(cause)

/**
 * Gemini Nano through Android's AICore, which runs it on the phone: nothing given to it leaves the
 * device. Not every phone has it, and AICore only answers the app on screen.
 *
 * The client is made on first use, since ML Kit reports usage to Google from then on; with the
 * setting off it is never made.
 */
class OnDeviceModel {
    private val model: GenerativeModel by lazy { Generation.getClient() }
    private val downloading = Mutex()

    private val _state = MutableStateFlow(ModelState.Unknown)
    val state: StateFlow<ModelState> = _state

    /** Asks AICore again, and fetches the model if the phone can run it but does not have it. */
    suspend fun prepare() {
        _state.value = check()
        if (_state.value == ModelState.Downloadable) download()
    }

    private suspend fun check(): ModelState = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> ModelState.Ready
            FeatureStatus.DOWNLOADABLE -> ModelState.Downloadable
            FeatureStatus.DOWNLOADING -> ModelState.Downloading
            else -> ModelState.Unsupported
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Thrown where AICore is missing altogether.
        Log.i(TAG, "No on-device model", e)
        ModelState.Unsupported
    }

    private suspend fun download(): Unit = downloading.withLock {
        if (_state.value == ModelState.Ready) return
        _state.value = ModelState.Downloading
        try {
            model.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadCompleted -> _state.value = ModelState.Ready
                    is DownloadStatus.DownloadFailed -> {
                        Log.w(TAG, "Model download failed", status.e)
                        _state.value = ModelState.Downloadable
                    }
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Model download failed", e)
            _state.value = check()
        }
    }

    /**
     * The summary of [missed] in [channel], growing as the model writes it. [combined] marks each
     * line with its channel.
     *
     * AICore checks every request against Google's safety rules and says no more than that it
     * refused. Then the few messages it objects to are searched out, left out of the summary and
     * handed back, so the user sees what is missing and why.
     */
    fun summarize(
        channel: String,
        missed: List<ChatItem>,
        combined: Boolean,
        language: String,
        ownLogin: String?,
    ): Flow<Summary> = flow {
        val entries = missed.mapNotNull { item -> CatchUp.line(item, combined)?.let { item to it } }
        // Given nothing, the model sums up its own instructions.
        if (entries.isEmpty()) return@flow
        val prompt = CatchUp.prompt(channel, language, ownLogin)
        val lines = entries.map { it.second }
        if (write(lines, prompt) { emit(Summary(it)) } != null) return@flow

        val refused = refusedIn(lines, prompt)
        val leftOut = refused.map { entries[it].first }
        val kept = lines.filterIndexed { i, _ -> i !in refused }
        if (kept.isEmpty()) throw ModelFailure(ModelTrouble.Refused)
        val text = write(kept, prompt) { emit(Summary(it, leftOut)) } ?: throw ModelFailure(ModelTrouble.Refused)
        emit(Summary(text, leftOut))
    }

    /** Each try asks for a single token, so it costs little more than the check itself. */
    private suspend fun refusedIn(lines: List<String>, prompt: String): Set<Int> =
        CatchUp.refused(lines.size) { from, to -> passes(lines.subList(from, to), prompt) }

    private suspend fun passes(lines: List<String>, prompt: String): Boolean = attempt {
        model.generateContent(request(prompt, lines, CatchUp.BUDGET, maxTokens = 1))
    } != null

    /**
     * Has the model write about [lines] after [prompt], passing on the text so far. Null when the
     * safety check refused it. A prompt the model finds too long is tried once more with half the
     * chat.
     */
    private suspend fun write(lines: List<String>, prompt: String, onText: suspend (String) -> Unit): String? {
        var budget = CatchUp.BUDGET
        while (true) {
            try {
                return attempt {
                    val text = StringBuilder()
                    model.generateContentStream(request(prompt, lines, budget, MAX_OUTPUT_TOKENS)).collect { chunk ->
                        chunk.candidates.firstOrNull()?.text?.let {
                            text.append(it)
                            onText(text.toString())
                        }
                    }
                    text.toString()
                }
            } catch (e: TooLarge) {
                if (budget != CatchUp.BUDGET) throw ModelFailure(ModelTrouble.Other, e)
                budget /= 2
            }
        }
    }

    private fun request(prompt: String, lines: List<String>, budget: Int, maxTokens: Int): GenerateContentRequest =
        generateContentRequest(TextPart(prompt + CatchUp.fit(lines, budget))) {
            // Low, so the summary sticks to what the chat said.
            temperature = 0.2f
            maxOutputTokens = maxTokens
        }

    private class TooLarge(cause: Throwable) : Exception(cause)

    /** Runs [block]; null if the safety check refused the request. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: GenAiException) {
        when (e.errorCode) {
            GenAiException.ErrorCode.REQUEST_TOO_LARGE -> throw TooLarge(e)
            // "Request doesn't pass certain policy check"; ML Kit has no code of its own for it.
            GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR -> null
            else -> {
                Log.w(TAG, "Summary failed with error ${e.errorCode}", e)
                throw ModelFailure(troubleOf(e.errorCode), e)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ModelFailure) {
        throw e
    } catch (e: TooLarge) {
        throw e
    } catch (e: Exception) {
        // AICore missing or gone since the check; ML Kit does not always wrap that.
        throw ModelFailure(ModelTrouble.Other, e)
    }

    private fun troubleOf(code: Int) = when (code) {
        GenAiException.ErrorCode.BUSY -> ModelTrouble.Busy
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> ModelTrouble.Quota
        GenAiException.ErrorCode.NOT_AVAILABLE, GenAiException.ErrorCode.NOT_SUPPORTED,
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE, GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> ModelTrouble.Unsupported
        else -> ModelTrouble.Other
    }

    private companion object {
        const val TAG = "OnDeviceModel"
        /** Three short bullet points; also keeps a model that rambles from running on. */
        const val MAX_OUTPUT_TOKENS = 256
    }
}
