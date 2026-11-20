package me.lesh.chatter.net

import me.lesh.chatter.debug.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.random.Random
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

class HttpException(val code: Int, val body: String) : IOException("HTTP $code: $body") {
    /** The human-readable `message` field of a Twitch API error, if present. */
    val apiMessage: String?
        get() = runCatching { AppJson.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
}

/** Runs the request and returns the body as text. Throws [HttpException] on non-2xx. */
suspend fun OkHttpClient.fetch(request: Request): String = fetchDecoding(request) { it.bufferedReader().readText() }

/**
 * Runs the request and hands the streaming body to [decode].
 *
 * Every failure while reading resumes the caller. An exception thrown out of [Callback.onResponse]
 * is only logged by OkHttp, and the caller would wait forever.
 */
suspend fun <T> OkHttpClient.fetchDecoding(request: Request, decode: (InputStream) -> T): T =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw HttpException(it.code, errorText(it))
                        cont.resume(decode(it.body.byteStream()))
                    }
                } catch (e: Throwable) {
                    // Already resumed or cancelled.
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        })
    }

/** The start of an error body for the exception message. Error pages can be any size. */
private fun errorText(response: Response): String =
    runCatching { response.peekBody(ERROR_PEEK_BYTES).string().take(200) }.getOrDefault("")

private const val ERROR_PEEK_BYTES = 4096L

/**
 * A JSON response decoded straight from the stream; emote lists of busy channels are a few hundred
 * kilobytes.
 */
@OptIn(ExperimentalSerializationApi::class)
suspend inline fun <reified T> OkHttpClient.getJson(url: String, headers: Map<String, String> = emptyMap()): T {
    val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    return fetchDecoding(request) { AppJson.decodeFromStream<T>(it) }
}

/**
 * Runs [block] up to [attempts] times while it fails in a way that may pass, waiting a little longer
 * each time. Emote and badge providers often miss one request and answer the next, and one timeout
 * should not put an outage note on screen.
 */
suspend fun <T> retryTransient(
    what: String,
    attempts: Int = 3,
    firstDelayMs: Long = 1_000,
    block: suspend () -> T,
): T {
    var wait = firstDelayMs
    repeat(attempts - 1) { attempt ->
        try {
            return block()
        } catch (e: IOException) {
            if (!e.isTransientFailure()) throw e
            AppLog.i("Http", "$what failed, trying again (${attempt + 2}/$attempts): ${e.message}")
        }
        // Some jitter, so many phones that lost a provider at once do not return in step.
        delay(wait + Random.nextLong(wait / 2))
        wait *= 2
    }
    return block()
}

/**
 * Timeouts, dropped connections and server errors. Not 429: a provider that asks to slow down gets
 * the longer wait of the app's own retries. Not 408 either, which OkHttp already repeats once.
 */
private fun IOException.isTransientFailure(): Boolean = when (this) {
    is HttpException -> code in 500..599
    is SSLPeerUnverifiedException -> false
    else -> generateSequence<Throwable>(this) { it.cause }.none { it is CertificateException }
}

/** Like [getJson], but null for a 404, e.g. a channel without a BTTV or 7TV account. */
suspend inline fun <reified T> OkHttpClient.getJsonOrNull(url: String, headers: Map<String, String> = emptyMap()): T? =
    try {
        getJson<T>(url, headers)
    } catch (e: HttpException) {
        if (e.code == 404) null else throw e
    }

suspend inline fun <reified T> OkHttpClient.postForm(url: String, fields: Map<String, String>): T {
    val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
    return AppJson.decodeFromString(fetch(Request.Builder().url(url).post(body).build()))
}

/** POST/PUT/PATCH/DELETE with an optional JSON body. Returns the response body text. */
suspend fun OkHttpClient.send(method: String, url: String, headers: Map<String, String>, json: String? = null): String {
    val body = json?.toRequestBody("application/json".toMediaType())
        ?: if (method == "DELETE") null else ByteArray(0).toRequestBody()
    val request = Request.Builder().url(url).method(method, body)
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .build()
    return fetch(request)
}
