package app.opah.tv.playback.compatibility

import java.util.ArrayList
import java.util.Collections

private const val MAX_RESOURCE_REQUESTS = 32
private const val MAX_RESOURCE_CANDIDATES_PER_REQUEST = 16
private const val MAX_RESOURCE_PRIORITY = 1_000
private val RESOURCE_REQUEST_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")

private fun <T> boundedResourceSnapshot(
    values: Collection<T>,
    maximumSize: Int,
    label: String,
): List<T> {
    val snapshot = ArrayList<T>(minOf(values.size, maximumSize))
    val iterator = values.iterator()
    while (iterator.hasNext()) {
        require(snapshot.size < maximumSize) { "$label exceeds its maximum size" }
        snapshot += iterator.next()
    }
    return Collections.unmodifiableList(snapshot)
}

@JvmInline
value class PlaybackResourceRequestId(val value: String) {
    init {
        require(RESOURCE_REQUEST_ID_PATTERN.matches(value)) {
            "Playback resource request ID must be a bounded opaque identifier"
        }
    }
}

/**
 * Decoder capacity established by a trusted device-capability adapter. Unknown devices should use
 * [conservative], which permits one ordinary live decoder and no explicit software fallback.
 */
data class PlaybackResourceCapacity(
    val maximumConcurrentDecoders: Int,
    val maximumAggregatePixels: Long,
    val maximumSingleStreamPixels: Long,
    val unknownDimensionPixelCharge: Long,
    val maximumSoftwareDecoders: Int,
    val maximumMonitorPromotions: Int,
    val maximumAudioStreams: Int,
) {
    init {
        require(maximumConcurrentDecoders in 0..MAX_RESOURCE_REQUESTS)
        require(maximumAggregatePixels > 0)
        require(maximumSingleStreamPixels in 1..maximumAggregatePixels)
        require(unknownDimensionPixelCharge in 1..maximumAggregatePixels)
        require(maximumSoftwareDecoders in 0..maximumConcurrentDecoders)
        require(maximumMonitorPromotions in 0..minOf(2, maximumConcurrentDecoders))
        require(maximumAudioStreams in 0..minOf(1, maximumConcurrentDecoders))
    }

    companion object {
        private const val FULL_HD_PIXELS = 1_920L * 1_080L

        fun conservative(): PlaybackResourceCapacity = PlaybackResourceCapacity(
            maximumConcurrentDecoders = 1,
            maximumAggregatePixels = FULL_HD_PIXELS,
            maximumSingleStreamPixels = FULL_HD_PIXELS,
            unknownDimensionPixelCharge = FULL_HD_PIXELS,
            maximumSoftwareDecoders = 0,
            maximumMonitorPromotions = 1,
            maximumAudioStreams = 1,
        )
    }
}

/** One logical playback consumer with a bounded, already-authorized candidate ladder. */
class PlaybackResourceRequest(
    val id: PlaybackResourceRequestId,
    val purpose: PlaybackPurpose,
    val priority: Int,
    candidates: Collection<PlaybackCandidate>,
    val resourcePolicy: PlaybackResourcePolicy = PlaybackResourcePolicy.defaultFor(purpose),
    val currentlyAllocatedCandidate: PlaybackCandidateKey? = null,
) {
    val candidates: List<PlaybackCandidate> = boundedResourceSnapshot(
        candidates,
        MAX_RESOURCE_CANDIDATES_PER_REQUEST,
        "Playback resource candidate ladder",
    )

    init {
        require(priority in 0..MAX_RESOURCE_PRIORITY)
        require(this.candidates.isNotEmpty())
        require(this.candidates.map(PlaybackCandidate::key).distinct().size == this.candidates.size) {
            "A resource request cannot contain duplicate playback candidates"
        }
        require(
            resourcePolicy.resourceClass == PlaybackResourcePolicy.defaultFor(purpose).resourceClass,
        ) { "Playback purpose and resource class must agree" }
        require(
            currentlyAllocatedCandidate == null ||
                this.candidates.any { it.key() == currentlyAllocatedCandidate },
        ) { "The retained allocation must be present in the candidate ladder" }
    }
}

data class PlaybackResourceAllocation(
    val requestId: PlaybackResourceRequestId,
    val purpose: PlaybackPurpose,
    val candidate: PlaybackCandidate,
    val chargedPixels: Long,
)

enum class PlaybackResourceDenialReason {
    NO_POLICY_COMPATIBLE_CANDIDATE,
    ATTEMPT_ID_REPLAY,
    DECODER_LIMIT,
    MONITOR_PROMOTION_LIMIT,
    PIXEL_BUDGET,
    SOFTWARE_DECODER_LIMIT,
    AUDIO_OWNER_LIMIT,
}

data class PlaybackResourceDenial(
    val requestId: PlaybackResourceRequestId,
    val reason: PlaybackResourceDenialReason,
)

class PlaybackResourcePlan internal constructor(
    allocations: List<PlaybackResourceAllocation>,
    denials: List<PlaybackResourceDenial>,
) {
    val allocations: List<PlaybackResourceAllocation> =
        Collections.unmodifiableList(ArrayList(allocations))
    val denials: List<PlaybackResourceDenial> = Collections.unmodifiableList(ArrayList(denials))

    fun allocationFor(requestId: PlaybackResourceRequestId): PlaybackResourceAllocation? =
        allocations.firstOrNull { it.requestId == requestId }
}

/**
 * Pure desired-state planner. Callers release any old lease absent from the returned allocations
 * before opening newly granted candidates; the planner itself never resolves routes or owns a
 * player. Replanning the same requests is deterministic and cannot over-allocate capacity.
 */
class PlaybackResourcePlanner {
    fun plan(
        capacity: PlaybackResourceCapacity,
        requests: Collection<PlaybackResourceRequest>,
    ): PlaybackResourcePlan {
        val requestSnapshot = boundedResourceSnapshot(
            requests,
            MAX_RESOURCE_REQUESTS,
            "Playback resource request set",
        )
        require(requestSnapshot.map(PlaybackResourceRequest::id).distinct().size == requestSnapshot.size) {
            "Playback resource request IDs must be unique"
        }

        val allocations = ArrayList<PlaybackResourceAllocation>()
        val denials = ArrayList<PlaybackResourceDenial>()
        var chargedPixels = 0L
        var softwareDecoders = 0
        var monitorPromotions = 0
        var audioStreams = 0

        requestSnapshot.sortedWith(REQUEST_ORDER).forEach { request ->
            val policyCandidates = request.orderedCandidates().filter { candidate ->
                request.resourcePolicy.accepts(candidate)
            }
            val denial = when {
                policyCandidates.isEmpty() -> PlaybackResourceDenialReason.NO_POLICY_COMPATIBLE_CANDIDATE
                allocations.size >= capacity.maximumConcurrentDecoders ->
                    PlaybackResourceDenialReason.DECODER_LIMIT
                request.purpose == PlaybackPurpose.MONITOR &&
                    monitorPromotions >= capacity.maximumMonitorPromotions ->
                    PlaybackResourceDenialReason.MONITOR_PROMOTION_LIMIT
                else -> null
            }
            if (denial != null) {
                denials += PlaybackResourceDenial(request.id, denial)
                return@forEach
            }

            var sawPixelBlock = false
            var sawSoftwareBlock = false
            var sawAudioBlock = false
            val selected = policyCandidates.firstOrNull { candidate ->
                val pixels = candidate.media.chargedPixels(capacity)
                val pixelAllowed = pixels <= capacity.maximumSingleStreamPixels &&
                    chargedPixels <= capacity.maximumAggregatePixels - pixels
                val usesSoftware = candidate.decoderMode == DecoderMode.ALLOW_SOFTWARE
                val softwareAllowed = !usesSoftware ||
                    softwareDecoders < capacity.maximumSoftwareDecoders
                val usesAudio = candidate.audioMode == AudioMode.WITH_AUDIO
                val audioAllowed = !usesAudio || audioStreams < capacity.maximumAudioStreams
                sawPixelBlock = sawPixelBlock || !pixelAllowed
                sawSoftwareBlock = sawSoftwareBlock || !softwareAllowed
                sawAudioBlock = sawAudioBlock || !audioAllowed
                pixelAllowed && softwareAllowed && audioAllowed
            }
            if (selected == null) {
                denials += PlaybackResourceDenial(
                    request.id,
                    if (sawPixelBlock) {
                        PlaybackResourceDenialReason.PIXEL_BUDGET
                    } else if (sawSoftwareBlock) {
                        PlaybackResourceDenialReason.SOFTWARE_DECODER_LIMIT
                    } else if (sawAudioBlock) {
                        PlaybackResourceDenialReason.AUDIO_OWNER_LIMIT
                    } else {
                        PlaybackResourceDenialReason.NO_POLICY_COMPATIBLE_CANDIDATE
                    },
                )
                return@forEach
            }

            val pixels = selected.media.chargedPixels(capacity)
            chargedPixels += pixels
            if (selected.decoderMode == DecoderMode.ALLOW_SOFTWARE) softwareDecoders += 1
            if (request.purpose == PlaybackPurpose.MONITOR) monitorPromotions += 1
            if (selected.audioMode == AudioMode.WITH_AUDIO) audioStreams += 1
            allocations += PlaybackResourceAllocation(
                requestId = request.id,
                purpose = request.purpose,
                candidate = selected,
                chargedPixels = pixels,
            )
        }
        return PlaybackResourcePlan(allocations, denials)
    }

    private fun PlaybackResourceRequest.orderedCandidates(): List<PlaybackCandidate> {
        val originalOrder = candidates.withIndex().associate { it.value.key() to it.index }
        val comparator = if (purpose.prefersLowerPixelCandidates) {
            compareBy<PlaybackCandidate> {
                it.media.knownPixelsOrNull() ?: Long.MAX_VALUE
            }
                .thenByDescending { it.key() == currentlyAllocatedCandidate }
                .thenBy { originalOrder.getValue(it.key()) }
        } else {
            compareByDescending<PlaybackCandidate> { it.key() == currentlyAllocatedCandidate }
                .thenBy { originalOrder.getValue(it.key()) }
        }
        return candidates.sortedWith(comparator)
    }

    private fun PlaybackResourcePolicy.accepts(candidate: PlaybackCandidate): Boolean {
        if (!audioPermitted && candidate.audioMode == AudioMode.WITH_AUDIO) return false
        if (!softwareDecoderPermitted && candidate.decoderMode == DecoderMode.ALLOW_SOFTWARE) {
            return false
        }
        val candidateLimit = maximumCandidatePixels ?: return true
        val pixels = candidate.media.knownPixelsOrNull() ?: return false
        return pixels <= candidateLimit
    }

    private fun PlaybackMediaMetadata.knownPixelsOrNull(): Long? {
        val knownWidth = width ?: return null
        val knownHeight = height ?: return null
        return knownWidth.toLong() * knownHeight.toLong()
    }

    private fun PlaybackMediaMetadata.chargedPixels(capacity: PlaybackResourceCapacity): Long =
        knownPixelsOrNull() ?: capacity.unknownDimensionPixelCharge

    private val PlaybackPurpose.prefersLowerPixelCandidates: Boolean
        get() = this == PlaybackPurpose.PICTURE_IN_PICTURE ||
            this == PlaybackPurpose.CAMERA_GROUP ||
            this == PlaybackPurpose.MONITOR

    private companion object {
        val REQUEST_ORDER = compareByDescending<PlaybackResourceRequest> {
            it.resourcePolicy.resourceClass.priorityTier
        }
            .thenByDescending(PlaybackResourceRequest::priority)
            .thenByDescending { it.currentlyAllocatedCandidate != null }
            .thenBy { it.id.value }

        val PlaybackResourceClass.priorityTier: Int
            get() = when (this) {
                PlaybackResourceClass.PRIMARY -> 5
                PlaybackResourceClass.DIAGNOSTIC -> 4
                PlaybackResourceClass.SECONDARY -> 3
                PlaybackResourceClass.MONITOR_PROMOTION -> 2
                PlaybackResourceClass.MULTI_VIEW -> 1
            }
    }
}
