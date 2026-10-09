package dev.chatter.app.badges

import dev.chatter.app.net.TrustedImages
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A 7TV paint: what a name is drawn with instead of its color. Colors are ARGB; 7TV sends RGBA.
 * Free of Compose, so the UI decides how to draw it.
 */
data class NamePaint(
    val id: String,
    val kind: Kind,
    /** The stops along the gradient, by position from 0 to 1; empty for an image. */
    val stops: List<Stop>,
    /** Linear only: CSS degrees, 0 pointing up and 90 to the right. */
    val angle: Float = 0f,
    /** The stops repeat along the gradient, like CSS's repeating gradients. */
    val repeat: Boolean = false,
    /** Image only. */
    val imageUrl: String? = null,
    /** Drop shadows behind the name, in the order 7TV lists them. */
    val shadows: List<Shadow> = emptyList(),
    /** What to draw until the gradient's place or the image is known. */
    val fallbackColor: Int? = null,
) {
    enum class Kind { Linear, Radial, Image }

    data class Stop(val at: Float, val color: Int)

    /** Offsets and blur radius in density-independent pixels. */
    data class Shadow(val x: Float, val y: Float, val radius: Float, val color: Int)

    companion object {
        /**
         * The `data` of a 7TV `cosmetic.create` for a paint, or null if Chatter cannot draw it.
         * Pictures are only taken from 7TV's own hosts; see [TrustedImages].
         */
        fun parse(data: JsonObject): NamePaint? {
            val id = data.string("id") ?: return null
            val kind = when (data.string("function")) {
                "LINEAR_GRADIENT" -> Kind.Linear
                "RADIAL_GRADIENT" -> Kind.Radial
                "URL" -> Kind.Image
                else -> return null
            }
            val stops = data.objects("stops").mapNotNull { stop ->
                val at = stop.primitive("at")?.floatOrNull ?: return@mapNotNull null
                val color = stop.primitive("color")?.longOrNull ?: return@mapNotNull null
                Stop(at.coerceIn(0f, 1f), rgbaToArgb(color))
            }.sortedBy { it.at }
            val imageUrl = if (kind == Kind.Image) TrustedImages.url(data.string("image_url").orEmpty()) ?: return null else null
            if (kind != Kind.Image && stops.size < 2) return null
            val shadows = data.objects("shadows").mapNotNull { shadow ->
                Shadow(
                    x = shadow.primitive("x_offset")?.floatOrNull ?: 0f,
                    y = shadow.primitive("y_offset")?.floatOrNull ?: 0f,
                    radius = shadow.primitive("radius")?.floatOrNull ?: 0f,
                    color = rgbaToArgb(shadow.primitive("color")?.longOrNull ?: return@mapNotNull null),
                )
            }
            return NamePaint(
                id = id,
                kind = kind,
                stops = stops,
                angle = data.primitive("angle")?.floatOrNull ?: 0f,
                repeat = data.primitive("repeat")?.booleanOrNull ?: false,
                imageUrl = imageUrl,
                shadows = shadows,
                fallbackColor = data.primitive("color")?.longOrNull?.let(::rgbaToArgb) ?: stops.firstOrNull()?.color,
            )
        }

        /** 7TV packs colors as a signed 32-bit RGBA number. */
        private fun rgbaToArgb(rgba: Long): Int {
            val bits = rgba and 0xFFFFFFFFL
            return (((bits and 0xFF) shl 24) or (bits ushr 8)).toInt()
        }

        // 7TV's answers are not checked against a schema, so a field of the wrong kind is skipped
        // instead of throwing.
        private fun JsonObject.primitive(key: String) = this[key] as? JsonPrimitive
        private fun JsonObject.string(key: String) = primitive(key)?.contentOrNull
        private fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    }
}
