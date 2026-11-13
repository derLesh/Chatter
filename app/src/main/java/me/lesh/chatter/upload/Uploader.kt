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
 * [linkKey] means the answer is only the link, as catbox.moe sends it. [extraFields] and [headers]
 * go along with every upload; a header carries an API key.
 */
@Serializable
data class UploadHost(
    val url: String,
    val field: String,
    val linkKey: String,
    val deleteKey: String = "",
    val extraFields: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
) {
    val isValid: Boolean
        get() = url.startsWith(HTTPS) && url.length > HTTPS.length && this.field.isNotBlank()

    /** The site's name: its address without "https://" and path. */
    val domain: String get() = url.removePrefix(HTTPS).substringBefore('/')

    companion object {
        private const val HTTPS = "https://"
        val EMPTY = UploadHost(HTTPS, "file", "link")
    }
}

/** A site the user set up, by an [id] of its own so the settings can name it. */
@Serializable
data class CustomUploader(val id: String, val host: UploadHost)

/** A key a site takes besides the picture, by the name the site gives it. */
enum class SiteKey(val label: String, val required: Boolean) {
    /** catbox.moe's account hash: uploads then belong to the account and can be deleted again. */
    CatboxUserhash("Userhash", required = false),

    /**
     * The Client-ID of an Imgur app. Imgur takes no upload without one and registers no new apps
     * since late 2025, so it is the user's own, from an app they already have.
     */
    ImgurClientId("Client-ID", required = true),
}

/** The sites Chatter knows itself; two take a key. [deletesAt] is empty where nothing can be deleted. */
enum class PresetUploader(val id: String, val title: String, val favicon: Int, val key: SiteKey?, val deletesAt: String) {
    /** Known from Chatterino. Answers `{"link", "key", "delete"}`; the key deletes the picture again. */
    KappaLol("kappa.lol", "kappa.lol", R.drawable.uploader_kappa_lol, null, "https://kappa.lol/api/delete?key=…"),

    /** Answers with nothing but the link. Deleting needs the account's userhash. */
    Catbox("catbox.moe", "catbox.moe", R.drawable.uploader_catbox, SiteKey.CatboxUserhash, "https://catbox.moe/user/api.php"),

    /** Answers `{"data": {"link", "deletehash"}}`; the deletehash and the Client-ID delete again. */
    Imgur("imgur.com", "Imgur", R.drawable.uploader_imgur, SiteKey.ImgurClientId, "https://api.imgur.com/3/image/…"),

    /** Chatterino's first uploader. Answers with nothing but the link and cannot delete. */
    Nuuls("nuuls.com", "nuuls.com", R.drawable.uploader_nuuls, null, "");


    companion object {
        fun byId(id: String): PresetUploader? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One place a picture can go right now: a preset with its key, or a site of the user's own.
 * [favicon] is null for the user's own sites, whose icons are never loaded, so a site learns
 * nothing before a picture is sent there.
 */
data class UploadTarget(val id: String, val name: String, val favicon: Int?, val host: UploadHost, private val preset: PresetUploader?, private val key: String?) {
    val isCustom: Boolean get() = preset == null

    /** Whether a picture uploaded here can be deleted from the list of uploads. */
    val canDelete: Boolean
        get() = when (preset) {
            PresetUploader.KappaLol, PresetUploader.Imgur -> true
            PresetUploader.Catbox -> key != null
            PresetUploader.Nuuls -> false
            null -> host.deleteKey.isNotBlank()
        }

    /** How a picture uploaded here is deleted, read from the site's [answer] for [link]. */
    fun deletion(answer: String, link: String): Deletion? = when (preset) {
        PresetUploader.KappaLol -> UploadAnswer.text(answer, "key")?.let { Deletion("https://kappa.lol/api/delete?key=$it") }
        PresetUploader.Catbox -> key?.let {
            Deletion(
                CATBOX_API,
                method = "POST",
                form = mapOf("reqtype" to "deletefiles", "userhash" to it, "files" to link.substringAfterLast('/')),
            )
        }
        PresetUploader.Nuuls -> null
        PresetUploader.Imgur -> UploadAnswer.text(answer, "data.deletehash")?.let {
            Deletion("https://api.imgur.com/3/image/$it", method = "DELETE", headers = host.headers)
        }
        // Only the site knows what its deletion page wants, so it opens in the browser.
        null -> host.deleteKey.takeIf { it.isNotBlank() }?.let { UploadAnswer.link(answer, it) }?.let { Deletion(it, inBrowser = true) }
    }

    companion object {
        private const val CATBOX_API = "https://catbox.moe/user/api.php"

        /** [preset] with the user's [key], or null while a key it requires is missing. */
        fun of(preset: PresetUploader, key: String?): UploadTarget? {
            val set = key?.trim()?.takeIf { it.isNotEmpty() }
            if (preset.key?.required == true && set == null) return null
            val host = when (preset) {
                PresetUploader.KappaLol -> UploadHost("https://kappa.lol/api/upload", "file", "link")
                PresetUploader.Catbox -> UploadHost(
                    CATBOX_API, "fileToUpload", "",
                    extraFields = mapOf("reqtype" to "fileupload") + listOfNotNull(set?.let { "userhash" to it }),
                )
                PresetUploader.Nuuls -> UploadHost("https://i.nuuls.com/upload", "attachment", "")
                PresetUploader.Imgur -> UploadHost(
                    "https://api.imgur.com/3/image", "image", "data.link", "data.deletehash",
                    extraFields = mapOf("type" to "file"),
                    headers = mapOf("Authorization" to "Client-ID $set"),
                )
            }
            return UploadTarget(preset.id, preset.title, preset.favicon, host, preset, set)
        }

        fun of(custom: CustomUploader): UploadTarget? =
            custom.host.takeIf { it.isValid }?.let { UploadTarget(custom.id, it.domain, null, it, null, null) }
    }
}

/**
 * How to delete an uploaded picture: a request to the site, or for a site the user set up its
 * deletion page, opened in the browser. It may hold the site's key, so it never goes into the log.
 */
@Serializable
data class Deletion(
    val url: String,
    val method: String = "GET",
    val form: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val inBrowser: Boolean = false,
)

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

    /**
     * Whether a deletion the site answered without an error worked: a JSON answer says so in
     * `success` (kappa.lol, Imgur), catbox.moe answers in words.
     */
    fun deleted(body: String): Boolean {
        val json = runCatching { AppJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (json != null) return (json["success"] as? JsonPrimitive)?.contentOrNull == "true"
        return body.contains("deleted", ignoreCase = true)
    }

    /** "Authorization: Client-ID abc" as a header, or null when it is not one. */
    fun header(line: String): Pair<String, String>? {
        val name = line.substringBefore(':', "").trim()
        val value = line.substringAfter(':', "").trim()
        return if (name.isEmpty() || value.isEmpty() || name.any { it.isWhitespace() }) null else name to value
    }
}
