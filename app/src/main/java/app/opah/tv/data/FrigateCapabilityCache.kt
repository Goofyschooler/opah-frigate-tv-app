package app.opah.tv.data

import app.opah.tv.data.model.FrigateCapabilities
import app.opah.tv.data.model.ServerVersionInfo

enum class CapabilityInvalidationReason {
    LOGIN_OR_LOGOUT,
    SESSION_SCOPE_CHANGED,
    SERVER_VERSION_CHANGED,
    CONFIGURATION_REFRESHED,
    CAMERA_CATALOG_CHANGED,
    MODE_CHANGED,
    SERVER_RESTARTED,
    EXPLICIT_RETRY,
}

/**
 * Process-local cache for capability evidence. Keys contain hashes and counts,
 * not credentials, addresses, or camera names.
 */
class FrigateCapabilityCache {
    private var entry: Entry? = null

    @Synchronized
    fun getOrPut(key: CapabilityCacheKey, resolve: () -> FrigateCapabilities): FrigateCapabilities {
        entry?.takeIf { it.key == key }?.let { return it.capabilities }
        return resolve().also { entry = Entry(key, it) }
    }

    @Synchronized
    fun invalidate(reason: CapabilityInvalidationReason) {
        lastInvalidationReason = reason
        entry = null
    }

    @Volatile
    var lastInvalidationReason: CapabilityInvalidationReason? = null
        private set

    private data class Entry(
        val key: CapabilityCacheKey,
        val capabilities: FrigateCapabilities,
    )
}

data class CapabilityCacheKey(
    val normalizedVersion: String,
    val compatibility: String,
    val role: String,
    val allowedCameraCount: Int,
    val allowedCameraSetHash: Int,
    val configurationHash: Int,
    val birdseyeHash: Int,
    val ptzEvidenceHash: Int,
) {
    companion object {
        fun from(
            version: ServerVersionInfo,
            role: String,
            allowedCameras: Set<String>,
            configJson: String,
            birdseyeEvidence: Any,
            ptzEvidence: Any?,
        ): CapabilityCacheKey = CapabilityCacheKey(
            normalizedVersion = version.normalizedVersion,
            compatibility = version.compatibility.name,
            role = role.trim().lowercase(),
            allowedCameraCount = allowedCameras.size,
            allowedCameraSetHash = allowedCameras.sorted().hashCode(),
            configurationHash = configJson.hashCode(),
            birdseyeHash = birdseyeEvidence.hashCode(),
            ptzEvidenceHash = ptzEvidence.hashCode(),
        )
    }
}
