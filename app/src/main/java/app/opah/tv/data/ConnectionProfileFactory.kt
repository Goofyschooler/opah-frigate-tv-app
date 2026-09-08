package app.opah.tv.data

import app.opah.tv.data.model.ConnectionProfile
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ConnectionProfileFactory {
    fun targetsUnauthenticatedFrigatePort(rawApiBaseUrl: String): Boolean {
        val input = rawApiBaseUrl.trim()
        if (input.isBlank()) return false
        val candidate = if ("://" in input) input else "http://$input"
        return candidate.toHttpUrlOrNull()?.port == 5000
    }

    fun create(
        rawApiBaseUrl: String,
        username: String,
        rtspHostOverride: String? = null,
        rtspPort: Int = 8554,
    ): Result<ConnectionProfile> = runCatching {
        require(rtspPort in 1..65535) { "RTSP port must be between 1 and 65535." }

        val withScheme = rawApiBaseUrl.trim().let { input ->
            require(input.isNotBlank()) { "Frigate URL is required." }
            when {
                "://" in input -> input
                targetsUnauthenticatedFrigatePort(input) -> "http://$input"
                else -> "https://$input"
            }
        }
        val parsed = withScheme.toHttpUrlOrNull()
            ?: error("Enter a valid Frigate HTTP or HTTPS URL")
        require(parsed.scheme == "https" || parsed.scheme == "http") {
            "Frigate URL must use HTTPS or HTTP."
        }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "Do not embed credentials in the Frigate URL."
        }
        val unauthenticated = parsed.port == 5000
        require(username.isNotBlank() || unauthenticated) {
            "Username is required unless you connect directly to Frigate port 5000."
        }

        val normalizedPath = parsed.encodedPath
            .trimEnd('/')
            .removeSuffix("/api")
            .ifEmpty { "/" }
        val normalized = parsed.newBuilder()
            .encodedPath(normalizedPath)
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')

        val hostOverride = rtspHostOverride
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.removePrefix("[")
            ?.removeSuffix("]")

        ConnectionProfile(
            apiBaseUrl = normalized,
            username = if (unauthenticated) "anonymous" else username.trim(),
            rtspHostOverride = hostOverride,
            rtspPort = rtspPort,
        )
    }
}
