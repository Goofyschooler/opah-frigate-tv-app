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
    PROFILE_MODES,
    PROFILE_MODE_SWITCH,
    MOTION_SEARCH,
    ON_DEMAND_RECORDING,
    INSTANT_SNAPSHOT,
    MULTI_CAMERA_EXPORT,
    INCIDENT_MUTATION,
}

enum class FrigateCapabilityAvailability {
    AVAILABLE,
    NOT_CONFIGURED,
    NOT_PERMITTED,
    CAMERA_UNSUPPORTED,
    DEVICE_UNSUPPORTED,
    TEMPORARILY_UNAVAILABLE,
    AUTHENTICATION_REQUIRED,
    UNAVAILABLE,
    NOT_SUPPORTED,
    UNKNOWN,
}

enum class FrigateCapabilityReason {
    READY,
    SERVER_CONFIGURATION,
    ACCOUNT_PERMISSION,
    CAMERA_CAPABILITY,
    DEVICE_CAPABILITY,
    TRANSIENT_FAILURE,
    SIGN_IN_REQUIRED,
    SERVER_CONTRACT,
    UNDETERMINED,
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
    AUTHENTICATED_ROLE,
    CAMERA_CONFIGURATION,
    DEVICE_PROBE,
    RUNTIME_OPERATION,
    HTTP_UNAUTHORIZED,
    HTTP_FORBIDDEN,
    HTTP_NOT_FOUND,
    HTTP_CONFLICT,
    HTTP_UNPROCESSABLE,
    HTTP_RATE_LIMITED,
    HTTP_SERVER_FAILURE,
}

data class FrigateCapability(
    val availability: FrigateCapabilityAvailability,
    val evidence: FrigateCapabilityEvidence,
    val reason: FrigateCapabilityReason = availability.defaultReason(),
    val evidenceSet: Set<FrigateCapabilityEvidence> = setOf(evidence),
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

private fun FrigateCapabilityAvailability.defaultReason(): FrigateCapabilityReason = when (this) {
    FrigateCapabilityAvailability.AVAILABLE -> FrigateCapabilityReason.READY
    FrigateCapabilityAvailability.NOT_CONFIGURED -> FrigateCapabilityReason.SERVER_CONFIGURATION
    FrigateCapabilityAvailability.NOT_PERMITTED -> FrigateCapabilityReason.ACCOUNT_PERMISSION
    FrigateCapabilityAvailability.CAMERA_UNSUPPORTED -> FrigateCapabilityReason.CAMERA_CAPABILITY
    FrigateCapabilityAvailability.DEVICE_UNSUPPORTED -> FrigateCapabilityReason.DEVICE_CAPABILITY
    FrigateCapabilityAvailability.TEMPORARILY_UNAVAILABLE,
    FrigateCapabilityAvailability.UNAVAILABLE,
    -> FrigateCapabilityReason.TRANSIENT_FAILURE
    FrigateCapabilityAvailability.AUTHENTICATION_REQUIRED -> FrigateCapabilityReason.SIGN_IN_REQUIRED
    FrigateCapabilityAvailability.NOT_SUPPORTED -> FrigateCapabilityReason.SERVER_CONTRACT
    FrigateCapabilityAvailability.UNKNOWN -> FrigateCapabilityReason.UNDETERMINED
}
