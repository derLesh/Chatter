package dev.chatter.app.badges

import dev.chatter.app.net.TrustedImages
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A 7TV paint: what a name is drawn with instead of its color. Colors are ARGB; 7TV sends RGBA.
 * Free of Compose, so the UI decides how to draw it.
 */
data class NamePaint(
    val id: String,
    val name: String,
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
    /** What to draw while the gradient's place or the image is not known yet. */
    val fallbackColor: Int? = null,
) {
    enum class Kind { Linear, Radial, Image }

    data class Stop(val at: Float, val color: Int)

    /** Offsets and blur radius in density-independent pixels. */
    data class Shadow(val x: Float, val y: Float, val radius: Float, val color: Int)

    companion object {
        /**
         * The `data` of a 7TV `cosmetic.create` for a paint, or null if it is nothing Chatter can
         * draw. Images are only taken from 7TV's own hosts; see [TrustedImages].
         */
        fun parse(data: JsonObject): NamePaint? {
            val id = data.string("id") ?: return null
            val stops = (data["stops"] as? JsonArray).orEmpty().mapNotNull { stop ->
                val obj = stop as? JsonObject ?: return@mapNotNull null
                val at = obj["at"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
                val color = obj["color"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                Stop(at.coerceIn(0f, 1f), rgbaToArgb(color))
            }.sortedBy { it.at }
            val kind = when (data.string("function")) {
                "LINEAR_GRADIENT" -> Kind.Linear
                "RADIAL_GRADIENT" -> Kind.Radial
                "URL" -> Kind.Image
                else -> return null
            }
            val imageUrl = if (kind == Kind.Image) TrustedImages.url(data.string("image_url").orEmpty()) ?: return null else null
            if (kind != Kind.Image && stops.size < 2) return null
            val shadows = (data["shadows"] as? JsonArray).orEmpty().mapNotNull { shadow ->
                val obj = shadow as? JsonObject ?: return@mapNotNull null
                Shadow(
                    x = obj["x_offset"]?.jsonPrimitive?.floatOrNull ?: 0f,
                    y = obj["y_offset"]?.jsonPrimitive?.floatOrNull ?: 0f,
                    radius = obj["radius"]?.jsonPrimitive?.floatOrNull ?: 0f,
                    color = rgbaToArgb(obj["color"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null),
                )
            }
            return NamePaint(
                id = id,
                name = data.string("name").orEmpty(),
                kind = kind,
                stops = stops,
                angle = data["angle"]?.jsonPrimitive?.floatOrNull ?: 0f,
                repeat = data["repeat"]?.jsonPrimitive?.booleanOrNull ?: false,
                imageUrl = imageUrl,
                shadows = shadows,
                fallbackColor = data["color"]?.jsonPrimitive?.longOrNull?.let(::rgbaToArgb) ?: stops.firstOrNull()?.color,
            )
        }

        /** 7TV packs colors as a signed 32-bit RGBA number. */
        fun rgbaToArgb(rgba: Long): Int {
            val bits = rgba and 0xFFFFFFFFL
            return (((bits and 0xFF) shl 24) or (bits ushr 8)).toInt()
        }

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
