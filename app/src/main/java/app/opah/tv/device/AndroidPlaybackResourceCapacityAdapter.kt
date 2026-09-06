package app.opah.tv.device

import app.opah.tv.data.model.CodecCapability
import app.opah.tv.data.model.DeviceDiagnostics
import app.opah.tv.playback.compatibility.PlaybackResourceCapacity
import java.util.Locale

/**
 * Reads process-local MediaCodec diagnostics and reduces them to a bounded resource budget.
 *
 * The adapter intentionally exposes neither codec names nor device identity. Inspection failures
 * and evidence that cannot prove a safe multi-decoder budget retain the engine's conservative
 * single-decoder capacity.
 */
class AndroidPlaybackResourceCapacityAdapter internal constructor(
    private val diagnosticsSource: () -> DeviceDiagnostics,
) {
    constructor() : this(
        diagnosticsSource = { DeviceMediaCapabilityService().inspect() },
    )

    fun currentCapacity(): PlaybackResourceCapacity = runCatching {
        DeviceDiagnosticsPlaybackResourceCapacityMapper.map(diagnosticsSource())
    }.getOrElse {
        PlaybackResourceCapacity.conservative()
    }
}

/** Pure, identifier-free policy mapper kept separate from the Android MediaCodec query. */
internal object DeviceDiagnosticsPlaybackResourceCapacityMapper {
    fun map(diagnostics: DeviceDiagnostics): PlaybackResourceCapacity {
        val conservative = PlaybackResourceCapacity.conservative()
        if (diagnostics.apiLevel < MIN_TRUSTED_HARDWARE_CLASSIFICATION_API) return conservative
        if (diagnostics.codecs.size > MAX_CODEC_RECORDS) return conservative

        val normalizedCodecs = diagnostics.codecs.map { codec ->
            val normalizedMimeType = codec.mimeType.normalizedMimeTypeOrNull() ?: return conservative
            normalizedMimeType to codec
        }
        if (normalizedCodecs.map { it.first }.distinct().size != normalizedCodecs.size) {
            return conservative
        }

        val codecsByMimeType = normalizedCodecs.toMap()
        if (!codecsByMimeType.keys.containsAll(TRACKED_VIDEO_MIME_TYPES)) return conservative
        if (codecsByMimeType.keys.any { it.startsWith(VIDEO_MIME_PREFIX) && it !in TRACKED_VIDEO_MIME_TYPES }) {
            return conservative
        }

        val supportedVideoCodecs = TRACKED_VIDEO_MIME_TYPES
            .map(codecsByMimeType::getValue)
            .filter { it.decoders.isNotEmpty() }
        if (supportedVideoCodecs.isEmpty()) return conservative

        val provenPerCodecLimits = supportedVideoCodecs.map { codec ->
            codec.provenHardwareInstanceLimitOrNull() ?: return conservative
        }
        val provenConcurrentDecoders = provenPerCodecLimits
            .minOrNull()
            ?.coerceAtMost(MAX_PROVEN_CONCURRENT_DECODERS)
            ?: return conservative
        if (provenConcurrentDecoders < 2) return conservative

        return PlaybackResourceCapacity(
            maximumConcurrentDecoders = provenConcurrentDecoders,
            maximumAggregatePixels = MAX_AGGREGATE_PIXELS,
            maximumSingleStreamPixels = FULL_HD_PIXELS,
            unknownDimensionPixelCharge = FULL_HD_PIXELS,
            maximumSoftwareDecoders = 0,
            maximumMonitorPromotions = minOf(2, provenConcurrentDecoders),
            maximumAudioStreams = 1,
        )
    }

    private fun CodecCapability.provenHardwareInstanceLimitOrNull(): Int? {
        if (decoders.size > MAX_DECODER_RECORDS_PER_CODEC) return null
        val normalizedCodecMimeType = mimeType.normalizedMimeTypeOrNull() ?: return null
        val hardwareLimits = ArrayList<Int>(decoders.size)

        decoders.forEach { decoder ->
            if (decoder.mimeType.normalizedMimeTypeOrNull() != normalizedCodecMimeType) return null
            val hardwareAccelerated = decoder.hardwareAccelerated ?: return null
            val softwareOnly = decoder.softwareOnly ?: return null
            val reportedInstances = decoder.maxSupportedInstances
            if (reportedInstances == null || reportedInstances <= 0) return null
            if (hardwareAccelerated && softwareOnly) return null
            if (hardwareAccelerated && !softwareOnly) hardwareLimits += reportedInstances
        }

        // The minimum is deliberate: Media3 may select any eligible hardware decoder.
        return hardwareLimits.minOrNull()
    }

    private fun String.normalizedMimeTypeOrNull(): String? {
        if (isBlank() || trim() != this) return null
        return lowercase(Locale.ROOT)
    }

    private const val MIN_TRUSTED_HARDWARE_CLASSIFICATION_API = 29
    private const val MAX_CODEC_RECORDS = 16
    private const val MAX_DECODER_RECORDS_PER_CODEC = 32
    private const val MAX_PROVEN_CONCURRENT_DECODERS = 2
    private const val VIDEO_MIME_PREFIX = "video/"
    private const val AVC_MIME_TYPE = "video/avc"
    private const val HEVC_MIME_TYPE = "video/hevc"
    private const val FULL_HD_PIXELS = 1_920L * 1_080L
    private const val HD_PIXELS = 1_280L * 720L
    private const val MAX_AGGREGATE_PIXELS = FULL_HD_PIXELS + HD_PIXELS
    private val TRACKED_VIDEO_MIME_TYPES = setOf(AVC_MIME_TYPE, HEVC_MIME_TYPE)
}
