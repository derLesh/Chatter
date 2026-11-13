package me.lesh.chatter.upload

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.lesh.chatter.R
import me.lesh.chatter.net.AppJson

/**
 * A site set up the way Chatterino sets one up: a form POST to [url] with the picture in [field].
 * [linkKey] and [deleteKey] are fields of the JSON answer, "data.link" for a nested one; an empty
 * [linkKey] means the answer is only the link, as catbox.moe sends it. [extraFields] go along with
 * every upload.
 */
@Serializable
data class UploadHost(
    val url: String,
    val field: String,
    val linkKey: String,
    val deleteKey: String = "",
    val extraFields: Map<String, String> = emptyMap(),
) {
    val isValid: Boolean
        get() = url.startsWith(HTTPS) && url.length > HTTPS.length && this.field.isNotBlank()

    companion object {
        private const val HTTPS = "https://"
        val EMPTY_CUSTOM = UploadHost(HTTPS, "file", "link")
    }
}

/** Where pictures can go. The sites are ones that need no account. */
@Serializable
enum class Uploader(val title: String, val favicon: Int?) {
    /** Known from Chatterino. Answers `{"link", "key", "delete"}`; the key deletes the picture again. */
    KappaLol("kappa.lol", R.drawable.uploader_kappa_lol),

    /** Answers with nothing but the link, and cannot delete what was uploaded without an account. */
    Catbox("catbox.moe", R.drawable.uploader_catbox),

    /** The site set under Settings; its favicon is never loaded, so it learns nothing before a send. */
    Custom("", null);

    /** How to upload to this site, [custom] for [Custom]. */
    fun host(custom: UploadHost): UploadHost = when (this) {
        KappaLol -> UploadHost("https://kappa.lol/api/upload", "file", "link")
        Catbox -> UploadHost("https://catbox.moe/user/api.php", "fileToUpload", "", extraFields = mapOf("reqtype" to "fileupload"))
        Custom -> custom
    }

    /** How a picture uploaded here is deleted, read from the site's [answer]. */
    fun deletion(answer: String, host: UploadHost): Deletion? = when (this) {
        KappaLol -> UploadAnswer.text(answer, "key")?.let { Deletion("https://kappa.lol/api/delete?key=$it", inBrowser = false) }
        Catbox -> null
        Custom -> host.deleteKey.takeIf { it.isNotBlank() }
            ?.let { UploadAnswer.link(answer, it) }
            ?.let { Deletion(it, inBrowser = true) }
    }

    /** The name shown for this site: for [Custom] the address it uploads to. */
    fun name(custom: UploadHost): String =
        if (this == Custom) custom.url.removePrefix("https://").substringBefore('/') else title
}

/**
 * How to delete an uploaded picture. A site of Chatter's own is asked directly; for a site the user
 * set up, its deletion page opens in the browser, since only the site knows what that page wants.
 */
@Serializable
data class Deletion(val url: String, val inBrowser: Boolean)

/** A picture on a site: its [link] for the chat, and how to delete it again if the site allows. */
data class UploadedPicture(val link: String, val deletion: Deletion?)

/** Reads fields out of an upload site's answer; free of Android for the tests. */
object UploadAnswer {
    /** The web link at [key]; the whole answer if [key] is empty. Null without one. */
    fun link(body: String, key: String): String? {
        val link = if (key.isBlank()) body.trim() else text(body, key) ?: return null
        return link.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    /** The text at [key] of a JSON answer, "data.link" for a nested one. */
    fun text(body: String, key: String): String? {
        var element: JsonElement = runCatching { AppJson.parseToJsonElement(body) }.getOrNull() ?: return null
        for (part in key.trim().split('.')) element = (element as? JsonObject)?.get(part) ?: return null
        return (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Whether a deletion answered with success; kappa.lol says `{"success": true}`. */
    fun deleted(body: String): Boolean =
        runCatching { (AppJson.parseToJsonElement(body) as? JsonObject)?.get("success") }
            .getOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull == "true" } ?: false
}
