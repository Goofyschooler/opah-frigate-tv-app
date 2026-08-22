package app.opah.tv.data.model

/** Server features that can change by Frigate version or local configuration. */
enum class FrigateFeature {
    REVIEW_ITEMS,
    REVIEW_STATE,
    REVIEW_SUMMARY,
    MOTION_ACTIVITY,
    RECORDING_HISTORY,
    RECORDING_EXPORT,
    RECORDING_CLIP_DOWNLOAD,
    PTZ_CONTROL,
    BIRDSEYE,
    SYSTEM_INFORMATION,
    SEMANTIC_SEARCH,
    GENAI_REVIEW_SUMMARY,
    EXPORT_CASES,
    CUSTOM_EXPORTS,
}

enum class FrigateCapabilityAvailability {
    AVAILABLE,
    NOT_CONFIGURED,
    UNAVAILABLE,
    NOT_SUPPORTED,
    UNKNOWN,
}

/** Machine-readable evidence; the UI supplies its own short, user-friendly wording. */
enum class FrigateCapabilityEvidence {
    VALIDATED_API,
    COMPATIBLE_API_LINE,
    SERVER_CONFIGURATION,
    RUNTIME_STREAM_PROBE,
    RUNTIME_PTZ_PROBE,
    RUNTIME_PTZ_UNAVAILABLE,
    FEATURE_REQUIRES_FRIGATE_0_18,
    CONFIGURATION_DISABLED,
    CONFIGURATION_UNREADABLE,
    CAMERA_ACCESS_RESTRICTED,
    RUNTIME_STREAM_UNAVAILABLE,
    SERVER_VERSION_UNRECOGNIZED,
    SERVER_VERSION_UNSUPPORTED,
}

data class FrigateCapability(
    val availability: FrigateCapabilityAvailability,
    val evidence: FrigateCapabilityEvidence,
) {
    val available: Boolean get() = availability == FrigateCapabilityAvailability.AVAILABLE
}

data class FrigateCapabilities(
    private val values: Map<FrigateFeature, FrigateCapability>,
) {
    operator fun get(feature: FrigateFeature): FrigateCapability =
        values[feature] ?: UNKNOWN_CAPABILITY

    fun supports(feature: FrigateFeature): Boolean = get(feature).available

    fun asMap(): Map<FrigateFeature, FrigateCapability> = values.toMap()

    companion object {
        private val UNKNOWN_CAPABILITY = FrigateCapability(
            availability = FrigateCapabilityAvailability.UNKNOWN,
            evidence = FrigateCapabilityEvidence.SERVER_VERSION_UNRECOGNIZED,
        )

        fun unknown(): FrigateCapabilities = FrigateCapabilities(emptyMap())
    }
}
