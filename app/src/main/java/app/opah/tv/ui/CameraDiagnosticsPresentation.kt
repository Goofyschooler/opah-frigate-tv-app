package app.opah.tv.ui

import app.opah.tv.data.model.AppSettings
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.CodecCapability
import app.opah.tv.data.model.DeviceDiagnostics
import app.opah.tv.data.model.StreamMetadata
import app.opah.tv.data.model.StreamPreference

internal data class CameraTechnicalReport(
    val cameraId: String,
    val configuredStreamCount: Int,
    val defaultStreamPreference: String,
    val defaultConnection: String,
    val savedPlaybackChoice: String,
    val streams: List<CameraStreamTechnicalReport>,
)

internal data class CameraStreamTechnicalReport(
    val optionLabel: String,
    val streamId: String,
    val frigateStatus: String,
    val videoFormat: String,
    val resolution: String,
    val videoDecoderSupport: String,
    val videoDecoderNames: String,
    val audioFormat: String,
    val audioDecoderSupport: String,
    val audioDecoderNames: String,
)

internal fun cameraTechnicalReport(
    camera: Camera,
    metadataByStream: Map<String, StreamMetadata>,
    device: DeviceDiagnostics?,
    settings: AppSettings,
    savedPlaybackChoice: String?,
): CameraTechnicalReport = CameraTechnicalReport(
    cameraId = safeTechnicalIdentifier(camera.name),
    configuredStreamCount = camera.streams.size,
    defaultStreamPreference = when (settings.streamPreference) {
        StreamPreference.AUTOMATIC -> "Automatic"
        StreamPreference.MAIN -> "Best quality"
        StreamPreference.LOW_BANDWIDTH -> "Low bandwidth"
    },
    defaultConnection = if (settings.preferRtpTcp) "RTP over TCP preferred" else "Automatic transport",
    savedPlaybackChoice = savedPlaybackChoice ?: "None saved — Opah chooses automatically",
    streams = camera.streams.map { option ->
        val metadata = option.metadata ?: metadataByStream[option.streamName]
        val videoSupport = decoderSupport(metadata?.videoCodec?.mimeType, device)
        val audioSupport = decoderSupport(metadata?.audioCodec?.mimeType, device)
        CameraStreamTechnicalReport(
            optionLabel = safeTechnicalIdentifier(option.label),
            streamId = safeTechnicalIdentifier(option.streamName),
            frigateStatus = when {
                metadata == null -> "No stream details reported"
                metadata.available -> "Available"
                else -> "Unavailable"
            },
            videoFormat = codecDescription(
                displayName = metadata?.videoCodec?.displayName,
                mimeType = metadata?.videoCodec?.mimeType,
            ),
            resolution = if (metadata?.width != null && metadata.height != null) {
                "${metadata.width}×${metadata.height}"
            } else {
                "Not reported"
            },
            videoDecoderSupport = videoSupport.summary,
            videoDecoderNames = videoSupport.names,
            audioFormat = codecDescription(
                displayName = metadata?.audioCodec?.displayName,
                mimeType = metadata?.audioCodec?.mimeType,
            ),
            audioDecoderSupport = audioSupport.summary,
            audioDecoderNames = audioSupport.names,
        )
    },
)

private data class DecoderSupport(
    val summary: String,
    val names: String,
)

private fun decoderSupport(mimeType: String?, device: DeviceDiagnostics?): DecoderSupport {
    if (mimeType == null) return DecoderSupport("Unknown codec", "Not available")
    if (device == null) return DecoderSupport("TV inspection is still loading", "Not available")
    val capability = device.codecs.firstOrNull { it.mimeType.equals(mimeType, ignoreCase = true) }
        ?: return DecoderSupport("Not inspected by Opah", "Not available")
    if (capability.decoders.isEmpty()) {
        return DecoderSupport("Not advertised by this TV", "None advertised")
    }
    return DecoderSupport(
        summary = decoderSupportSummary(capability),
        names = capability.decoders.joinToString { decoder -> decoder.name },
    )
}

private fun decoderSupportSummary(capability: CodecCapability): String = buildList {
    add(
        when {
            capability.hasHardwareDecoder -> "Hardware decoder available"
            capability.decoders.any { it.softwareOnly == true } -> "Software decoder only"
            else -> "Decoder available"
        },
    )
    add("${capability.decoders.size} advertised")
    if (capability.decoders.any { it.adaptivePlayback }) add("adaptive playback")
    capability.decoders.mapNotNull { it.maxSupportedInstances }.maxOrNull()?.let { maximum ->
        add("up to $maximum instances")
    }
}.joinToString(" • ")

private fun codecDescription(displayName: String?, mimeType: String?): String = when {
    displayName == null -> "Not reported"
    mimeType == null -> displayName
    else -> "$displayName ($mimeType)"
}

private fun safeTechnicalIdentifier(value: String): String {
    val normalized = value.trim().replace(Regex("[\\r\\n\\t]+"), " ")
    if (normalized.isEmpty()) return "Not reported"
    if (SENSITIVE_IDENTIFIER_HINTS.containsMatchIn(normalized)) return "Hidden for privacy"
    return normalized.take(MAX_TECHNICAL_IDENTIFIER_LENGTH)
}

private val SENSITIVE_IDENTIFIER_HINTS = Regex(
    pattern = "(?i)(://|password\\s*=|passwd\\s*=|token\\s*=|api[_-]?key\\s*=|secret\\s*=)",
)
private const val MAX_TECHNICAL_IDENTIFIER_LENGTH = 160
