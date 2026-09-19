package dev.chatter.app.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
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

/** Runs the request asynchronously and returns the body as text. Throws [HttpException] on non-2xx. */
suspend fun OkHttpClient.fetch(request: Request): String = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

        override fun onResponse(call: Call, response: Response) {
            response.use {
                val body = it.body.string()
                if (it.isSuccessful) cont.resume(body)
                else cont.resumeWithException(HttpException(it.code, body.take(200)))
            }
        }
    })
}

suspend inline fun <reified T> OkHttpClient.getJson(url: String, headers: Map<String, String> = emptyMap()): T {
    val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    return AppJson.decodeFromString(fetch(request))
}

/** Like [getJson] but returns null for 404 (e.g. a channel that has no BTTV/7TV account). */
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
