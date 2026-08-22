package app.opah.tv.data

import app.opah.tv.data.model.BirdseyeStatus
import app.opah.tv.data.model.CameraPtzInfo
import app.opah.tv.data.model.FrigateCapabilities
import app.opah.tv.data.model.FrigateCapability
import app.opah.tv.data.model.FrigateCapabilityAvailability
import app.opah.tv.data.model.FrigateCapabilityEvidence
import app.opah.tv.data.model.FrigateFeature
import app.opah.tv.data.model.ServerVersionCompatibility
import app.opah.tv.data.model.ServerVersionInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Resolves API-contract support separately from features enabled on one Frigate server. */
class FrigateCapabilityResolver(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun resolve(
        version: ServerVersionInfo,
        configJson: String,
        allowedCameras: Set<String>,
        birdseye: BirdseyeStatus,
        birdseyePermitted: Boolean,
        ptzCameras: Map<String, CameraPtzInfo>? = null,
    ): FrigateCapabilities {
        val apiEvidence = apiEvidence(version)
        if (apiEvidence == null) {
            val evidence = when (version.compatibility) {
                ServerVersionCompatibility.UNSUPPORTED ->
                    FrigateCapabilityEvidence.SERVER_VERSION_UNSUPPORTED
                else -> FrigateCapabilityEvidence.SERVER_VERSION_UNRECOGNIZED
            }
            return FrigateCapabilities(
                FrigateFeature.entries.associateWith {
                    FrigateCapability(FrigateCapabilityAvailability.UNKNOWN, evidence)
                },
            )
        }

        val capabilities = FrigateFeature.entries.associateWith {
            FrigateCapability(FrigateCapabilityAvailability.AVAILABLE, apiEvidence)
        }.toMutableMap()

        val isFrigate018 = version.major == 0 && version.minor == 18
        if (!isFrigate018) {
            val unavailable = FrigateCapability(
                FrigateCapabilityAvailability.NOT_SUPPORTED,
                FrigateCapabilityEvidence.FEATURE_REQUIRES_FRIGATE_0_18,
            )
            capabilities[FrigateFeature.EXPORT_CASES] = unavailable
            capabilities[FrigateFeature.CUSTOM_EXPORTS] = unavailable
        }

        val root = runCatching { json.parseToJsonElement(configJson) as? JsonObject }
            .getOrNull()
        if (root == null) {
            CONFIGURATION_FEATURES.forEach { feature ->
                capabilities[feature] = FrigateCapability(
                    FrigateCapabilityAvailability.UNKNOWN,
                    FrigateCapabilityEvidence.CONFIGURATION_UNREADABLE,
                )
            }
            return FrigateCapabilities(capabilities)
        }

        val ptzConfigured = root.authorizedCameraHasOnvif(allowedCameras)
        capabilities[FrigateFeature.PTZ_CONTROL] = when {
            !ptzConfigured -> configuredCapability(false)
            ptzCameras == null -> configuredCapability(true)
            ptzCameras.values.any(CameraPtzInfo::hasControls) -> FrigateCapability(
                FrigateCapabilityAvailability.AVAILABLE,
                FrigateCapabilityEvidence.RUNTIME_PTZ_PROBE,
            )
            else -> FrigateCapability(
                FrigateCapabilityAvailability.UNAVAILABLE,
                FrigateCapabilityEvidence.RUNTIME_PTZ_UNAVAILABLE,
            )
        }
        capabilities[FrigateFeature.SEMANTIC_SEARCH] = configuredCapability(
            root.obj("semantic_search")?.bool("enabled") == true,
        )
        capabilities[FrigateFeature.GENAI_REVIEW_SUMMARY] = configuredCapability(
            root.hasReviewSummaryProvider() && root.reviewSummariesEnabled(allowedCameras),
        )
        capabilities[FrigateFeature.BIRDSEYE] = when {
            !birdseyePermitted -> FrigateCapability(
                FrigateCapabilityAvailability.UNAVAILABLE,
                FrigateCapabilityEvidence.CAMERA_ACCESS_RESTRICTED,
            )
            birdseye.playable -> FrigateCapability(
                FrigateCapabilityAvailability.AVAILABLE,
                FrigateCapabilityEvidence.RUNTIME_STREAM_PROBE,
            )
            birdseye.enabled && birdseye.restreamConfigured -> FrigateCapability(
                FrigateCapabilityAvailability.UNAVAILABLE,
                FrigateCapabilityEvidence.RUNTIME_STREAM_UNAVAILABLE,
            )
            else -> FrigateCapability(
                FrigateCapabilityAvailability.NOT_CONFIGURED,
                FrigateCapabilityEvidence.CONFIGURATION_DISABLED,
            )
        }

        return FrigateCapabilities(capabilities)
    }

    private fun apiEvidence(version: ServerVersionInfo): FrigateCapabilityEvidence? {
        val knownApiLine = version.major == 0 && version.minor in setOf(17, 18)
        if (!knownApiLine) return null
        return when (version.compatibility) {
            ServerVersionCompatibility.SUPPORTED -> FrigateCapabilityEvidence.VALIDATED_API
            ServerVersionCompatibility.COMPATIBLE_UNVERIFIED ->
                FrigateCapabilityEvidence.COMPATIBLE_API_LINE
            ServerVersionCompatibility.UNSUPPORTED,
            ServerVersionCompatibility.UNKNOWN,
            -> null
        }
    }

    private fun configuredCapability(configured: Boolean): FrigateCapability = FrigateCapability(
        availability = if (configured) {
            FrigateCapabilityAvailability.AVAILABLE
        } else {
            FrigateCapabilityAvailability.NOT_CONFIGURED
        },
        evidence = if (configured) {
            FrigateCapabilityEvidence.SERVER_CONFIGURATION
        } else {
            FrigateCapabilityEvidence.CONFIGURATION_DISABLED
        },
    )

    private fun JsonObject.authorizedCameraHasOnvif(allowedCameras: Set<String>): Boolean =
        obj("cameras").orEmpty().any { (cameraName, value) ->
            if (cameraName !in allowedCameras) return@any false
            val camera = value as? JsonObject ?: return@any false
            val onvif = camera.obj("onvif") ?: return@any false
            !onvif.string("host").isNullOrBlank()
        }

    private fun JsonObject.hasReviewSummaryProvider(): Boolean {
        val genai = obj("genai") ?: return false
        if (!genai.string("provider").isNullOrBlank()) return true
        return genai.values.any { value ->
            val provider = value as? JsonObject ?: return@any false
            if (provider.string("provider").isNullOrBlank()) return@any false
            val roles = (provider["roles"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }
                .orEmpty()
            roles.isEmpty() || roles.any { "review" in it && "summar" in it }
        }
    }

    private fun JsonObject.reviewSummariesEnabled(allowedCameras: Set<String>): Boolean {
        if (obj("review")?.obj("genai")?.bool("enabled") == true) return true
        return obj("cameras").orEmpty().any { (cameraName, value) ->
            cameraName in allowedCameras &&
                (value as? JsonObject)?.obj("review")?.obj("genai")?.bool("enabled") == true
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.bool(key: String): Boolean? = get(key)?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    private companion object {
        val CONFIGURATION_FEATURES = setOf(
            FrigateFeature.PTZ_CONTROL,
            FrigateFeature.BIRDSEYE,
            FrigateFeature.SEMANTIC_SEARCH,
            FrigateFeature.GENAI_REVIEW_SUMMARY,
        )
    }
}
