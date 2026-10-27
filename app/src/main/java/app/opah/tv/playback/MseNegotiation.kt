package app.opah.tv.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Never relay a remote error body to logs or the UI. */
internal fun acceptsMseNegotiation(text: String): Boolean {
    if (text.length > 16_384) return false
    return runCatching {
        val message = Json.parseToJsonElement(text).jsonObject
        if (message["type"]?.jsonPrimitive?.content != "mse") return false
        val mime = message["value"]?.jsonPrimitive?.content ?: return false
        val match = Regex("""video/mp4;\s*codecs="([^"]+)"""").matchEntire(mime) ?: return false
        val codecs = match.groupValues[1].split(',').map(String::trim)
        codecs.any { it.startsWith("avc1.") } && codecs.all {
            Regex("avc1\\.[0-9a-fA-F]{6}").matches(it) || it in setOf("mp4a.40.2", "mp4a.40.5")
        }
    }.getOrDefault(false)
}
