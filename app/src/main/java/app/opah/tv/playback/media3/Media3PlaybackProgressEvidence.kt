package app.opah.tv.playback.media3

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import app.opah.tv.playback.compatibility.DecoderImplementationEvidence
import app.opah.tv.playback.compatibility.PlaybackAttemptEventSink
import app.opah.tv.playback.compatibility.PlaybackAttemptId
import app.opah.tv.playback.compatibility.PlaybackAttemptSignal
import app.opah.tv.playback.compatibility.PlaybackDecoderEvidence
import app.opah.tv.playback.compatibility.SanitizedDecoderName
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/** Monotonic clock used to correlate Media3 callbacks with compatibility-runner deadlines. */
fun interface Media3EvidenceClock {
    fun elapsedRealtimeMillis(): Long
}

object AndroidMedia3EvidenceClock : Media3EvidenceClock {
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}

/**
 * Caps callback traffic without weakening the runner's requirement for recurring fresh evidence.
 * State remains constant-size for the lifetime of an attempt.
 */
data class Media3ProgressEvidencePolicy(
    val minimumVideoProgressIntervalMillis: Long = 250,
    val minimumAudioProgressIntervalMillis: Long = 250,
) {
    init {
        require(minimumVideoProgressIntervalMillis in 1..5_000)
        require(minimumAudioProgressIntervalMillis in 1..5_000)
    }
}

data class Media3DecoderEvidenceSnapshot(
    val video: PlaybackDecoderEvidence,
    val audio: PlaybackDecoderEvidence,
)

/**
 * Converts a Media3 decoder callback name into bounded, privacy-minimized evidence. Implementations
 * must never return the raw callback value as a [SanitizedDecoderName].
 */
fun interface Media3DecoderEvidenceMapper {
    fun map(decoderName: String): PlaybackDecoderEvidence
}

/**
 * Takes one device-codec snapshot up front so Media3 callback threads never enumerate codecs.
 * Decoder names are represented outside this adapter only by a stable one-way alias.
 * Before API 29 the platform exposes no authoritative hardware/software flags, so this mapper
 * recognizes only established software families and reports every other implementation unknown.
 */
class AndroidMedia3DecoderEvidenceMapper private constructor(
    private val implementationByNormalizedName: Map<String, DecoderImplementationEvidence>,
) : Media3DecoderEvidenceMapper {
    override fun map(decoderName: String): PlaybackDecoderEvidence {
        val normalized = decoderName.normalizedDecoderNameOrNull()
            ?: return PlaybackDecoderEvidence.UNKNOWN
        val implementation = implementationByNormalizedName[normalized]
            ?: conservativeSoftwareClassification(normalized)
        if (implementation == DecoderImplementationEvidence.UNKNOWN) {
            return PlaybackDecoderEvidence.UNKNOWN
        }
        val alias = opaqueDecoderAlias(normalized) ?: return PlaybackDecoderEvidence.UNKNOWN
        return PlaybackDecoderEvidence(
            implementation = implementation,
            sanitizedName = alias,
        )
    }

    override fun toString(): String = "AndroidMedia3DecoderEvidenceMapper([redacted])"

    companion object {
        /** Call during backend construction, rather than from a Media3 callback. */
        fun inspectCurrentDevice(): AndroidMedia3DecoderEvidenceMapper {
            return runCatching {
                fromCodecInfos(
                    MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList(),
                )
            }.getOrElse { AndroidMedia3DecoderEvidenceMapper(emptyMap()) }
        }

        internal fun fromClassifications(
            classifications: Map<String, DecoderImplementationEvidence>,
        ): AndroidMedia3DecoderEvidenceMapper {
            val normalized = LinkedHashMap<String, DecoderImplementationEvidence>()
            classifications.forEach { (name, implementation) ->
                name.normalizedDecoderNameOrNull()?.let { key ->
                    normalized.mergeConservatively(key, implementation)
                }
            }
            return AndroidMedia3DecoderEvidenceMapper(normalized.toMap())
        }

        private fun fromCodecInfos(
            codecInfos: List<MediaCodecInfo>,
        ): AndroidMedia3DecoderEvidenceMapper {
            val classifications = LinkedHashMap<String, DecoderImplementationEvidence>()
            codecInfos.asSequence()
                .filterNot { it.isEncoder }
                .forEach { codecInfo ->
                    val key = codecInfo.name.normalizedDecoderNameOrNull() ?: return@forEach
                    classifications.mergeConservatively(key, codecInfo.implementationEvidence())
                }
            return AndroidMedia3DecoderEvidenceMapper(classifications.toMap())
        }
    }
}

/**
 * Attempt-bound Media3 evidence bridge.
 *
 * Video progress is admitted only while the exact intended output generation is attached, its
 * surface has positive dimensions, and Media3 reports an actually rendered first frame. Audio
 * progress is admitted only when a non-empty buffer has been accepted by the AudioSink and the
 * AudioSink's own playback position then advances. General player position is deliberately unused.
 * All emitted signals retain [attemptId], so an already-in-flight callback remains safely
 * rejectable after cancellation by the compatibility runner.
 */
@UnstableApi
class Media3PlaybackProgressEvidenceSession(
    val attemptId: PlaybackAttemptId,
    attemptStartedElapsedMillis: Long,
    private val intendedVideoOutputGeneration: Long,
    private val eventSink: PlaybackAttemptEventSink,
    private val decoderEvidenceMapper: Media3DecoderEvidenceMapper,
    private val clock: Media3EvidenceClock = AndroidMedia3EvidenceClock,
    private val policy: Media3ProgressEvidencePolicy = Media3ProgressEvidencePolicy(),
) {
    init {
        require(intendedVideoOutputGeneration > 0) {
            "Video output generation must be positive"
        }
    }

    private val lock = Any()
    private val elapsedFloorMillis = attemptStartedElapsedMillis.also {
        require(it >= 0) { "Attempt start must not be negative" }
    }

    private var active = true
    private var intendedVideoOutputAttached = false
    private var intendedVideoSurfaceReady = false
    private var lastObservedElapsedMillis = elapsedFloorMillis
    private var firstFrameEmitted = false
    private var renderedVideoPathReady = false
    private var acceptedAudioData = false
    private var lastAudioSinkPositionUs: Long? = null
    private var lastVideoEmissionElapsedMillis: Long? = null
    private var lastAudioEmissionElapsedMillis: Long? = null
    private var videoSequence = 0L
    private var audioSequence = 0L
    private var videoDecoderEvidence = PlaybackDecoderEvidence.UNKNOWN
    private var audioDecoderEvidence = PlaybackDecoderEvidence.UNKNOWN

    val analyticsListener: AnalyticsListener = EvidenceAnalyticsListener()

    val videoFrameMetadataListener: VideoFrameMetadataListener =
        VideoFrameMetadataListener { _, _, _, _ ->
            recordVideoFrameAboutToRender(attemptId, intendedVideoOutputGeneration)
        }

    fun wrapAudioSink(delegate: AudioSink): AudioSink = EvidenceAudioSink(delegate)

    fun attachVideoOutput(
        requestedAttemptId: PlaybackAttemptId,
        outputGeneration: Long,
    ): Boolean = synchronized(lock) {
        if (
            !active ||
            requestedAttemptId != attemptId ||
            outputGeneration != intendedVideoOutputGeneration
        ) {
            return@synchronized false
        }
        intendedVideoOutputAttached = true
        intendedVideoSurfaceReady = false
        resetVideoPathLocked()
        true
    }

    fun recordVideoSurfaceSize(
        requestedAttemptId: PlaybackAttemptId,
        outputGeneration: Long,
        width: Int,
        height: Int,
    ): Boolean = synchronized(lock) {
        if (
            !active ||
            requestedAttemptId != attemptId ||
            outputGeneration != intendedVideoOutputGeneration ||
            !intendedVideoOutputAttached
        ) {
            return@synchronized false
        }
        val ready = width > 0 && height > 0
        intendedVideoSurfaceReady = ready
        if (!ready) resetVideoPathLocked()
        true
    }

    fun detachVideoOutput(
        requestedAttemptId: PlaybackAttemptId,
        outputGeneration: Long,
    ): Boolean = synchronized(lock) {
        if (
            requestedAttemptId != attemptId ||
            outputGeneration != intendedVideoOutputGeneration
        ) {
            return@synchronized false
        }
        intendedVideoOutputAttached = false
        intendedVideoSurfaceReady = false
        resetVideoPathLocked()
        true
    }

    /** Invalidates freshness without allowing an unrelated attempt to reset this session. */
    fun reset(requestedAttemptId: PlaybackAttemptId): Boolean = synchronized(lock) {
        if (!active || requestedAttemptId != attemptId) return@synchronized false
        resetVideoPathLocked()
        resetAudioPathLocked()
        videoDecoderEvidence = PlaybackDecoderEvidence.UNKNOWN
        audioDecoderEvidence = PlaybackDecoderEvidence.UNKNOWN
        true
    }

    /** Exact repeated cancellation is idempotent; a mismatched attempt cannot cancel this session. */
    fun cancel(requestedAttemptId: PlaybackAttemptId): Boolean = synchronized(lock) {
        if (requestedAttemptId != attemptId) return@synchronized false
        if (!active) return@synchronized true
        active = false
        intendedVideoOutputAttached = false
        intendedVideoSurfaceReady = false
        resetVideoPathLocked()
        resetAudioPathLocked()
        videoDecoderEvidence = PlaybackDecoderEvidence.UNKNOWN
        audioDecoderEvidence = PlaybackDecoderEvidence.UNKNOWN
        true
    }

    fun decoderEvidence(requestedAttemptId: PlaybackAttemptId): Media3DecoderEvidenceSnapshot? =
        synchronized(lock) {
            if (!active || requestedAttemptId != attemptId) return@synchronized null
            Media3DecoderEvidenceSnapshot(
                video = videoDecoderEvidence,
                audio = audioDecoderEvidence,
            )
        }

    internal fun recordVideoDecoderInitialized(
        requestedAttemptId: PlaybackAttemptId,
        decoderName: String,
    ) {
        if (!accepts(requestedAttemptId)) return
        val mapped = runCatching { decoderEvidenceMapper.map(decoderName) }
            .getOrDefault(PlaybackDecoderEvidence.UNKNOWN)
        synchronized(lock) {
            if (!active || requestedAttemptId != attemptId) return
            videoDecoderEvidence = mapped
        }
    }

    internal fun recordAudioDecoderInitialized(
        requestedAttemptId: PlaybackAttemptId,
        decoderName: String,
    ) {
        if (!accepts(requestedAttemptId)) return
        val mapped = runCatching { decoderEvidenceMapper.map(decoderName) }
            .getOrDefault(PlaybackDecoderEvidence.UNKNOWN)
        synchronized(lock) {
            if (!active || requestedAttemptId != attemptId) return
            audioDecoderEvidence = mapped
        }
    }

    internal fun recordRenderedFirstFrame(
        requestedAttemptId: PlaybackAttemptId,
        outputGeneration: Long = intendedVideoOutputGeneration,
    ) {
        val signal = synchronized(lock) {
            if (
                !acceptsVideoOutputLocked(requestedAttemptId, outputGeneration) ||
                !intendedVideoSurfaceReady
            ) {
                return@synchronized null
            }
            renderedVideoPathReady = true
            if (firstFrameEmitted) return@synchronized null
            firstFrameEmitted = true
            PlaybackAttemptSignal.FirstFrame(
                attemptId = attemptId,
                elapsedMillis = monotonicNowLocked(),
                decoderEvidence = videoDecoderEvidence,
            )
        }
        signal?.let(eventSink::emit)
    }

    internal fun recordVideoFrameAboutToRender(
        requestedAttemptId: PlaybackAttemptId,
        outputGeneration: Long = intendedVideoOutputGeneration,
    ) = recordVideoProgress(requestedAttemptId, outputGeneration)

    internal fun recordProcessedVideoFrameBatch(
        requestedAttemptId: PlaybackAttemptId,
        frameCount: Int,
        outputGeneration: Long = intendedVideoOutputGeneration,
    ) {
        if (frameCount <= 0) return
        recordVideoProgress(requestedAttemptId, outputGeneration)
    }

    private fun recordVideoProgress(
        requestedAttemptId: PlaybackAttemptId,
        outputGeneration: Long,
    ) {
        val signal = synchronized(lock) {
            if (
                !acceptsVideoOutputLocked(requestedAttemptId, outputGeneration) ||
                !intendedVideoSurfaceReady ||
                !renderedVideoPathReady
            ) {
                return@synchronized null
            }
            val now = monotonicNowLocked()
            if (!intervalReached(lastVideoEmissionElapsedMillis, now, policy.minimumVideoProgressIntervalMillis)) {
                return@synchronized null
            }
            val nextSequence = videoSequence.nextSequenceOrNull() ?: return@synchronized null
            videoSequence = nextSequence
            lastVideoEmissionElapsedMillis = now
            PlaybackAttemptSignal.VideoProgress(attemptId, now, nextSequence)
        }
        signal?.let(eventSink::emit)
    }

    internal fun recordAudioBufferAccepted(
        requestedAttemptId: PlaybackAttemptId,
        byteCount: Int,
    ) {
        if (byteCount <= 0) return
        synchronized(lock) {
            if (!active || requestedAttemptId != attemptId) return
            acceptedAudioData = true
        }
    }

    internal fun recordAudioSinkPosition(
        requestedAttemptId: PlaybackAttemptId,
        positionUs: Long,
    ) {
        val signal = synchronized(lock) {
            if (
                !active ||
                requestedAttemptId != attemptId ||
                !acceptedAudioData ||
                positionUs < 0 ||
                positionUs == AudioSink.CURRENT_POSITION_NOT_SET
            ) {
                return@synchronized null
            }
            val previousPositionUs = lastAudioSinkPositionUs
            lastAudioSinkPositionUs = positionUs
            if (previousPositionUs == null || positionUs <= previousPositionUs) {
                if (previousPositionUs != null && positionUs < previousPositionUs) {
                    acceptedAudioData = false
                }
                return@synchronized null
            }
            val now = monotonicNowLocked()
            if (!intervalReached(lastAudioEmissionElapsedMillis, now, policy.minimumAudioProgressIntervalMillis)) {
                return@synchronized null
            }
            val nextSequence = audioSequence.nextSequenceOrNull() ?: return@synchronized null
            audioSequence = nextSequence
            lastAudioEmissionElapsedMillis = now
            PlaybackAttemptSignal.AudioProgress(attemptId, now, nextSequence)
        }
        signal?.let(eventSink::emit)
    }

    internal fun resetAudioPath(requestedAttemptId: PlaybackAttemptId) {
        synchronized(lock) {
            if (!active || requestedAttemptId != attemptId) return
            resetAudioPathLocked()
        }
    }

    private fun accepts(requestedAttemptId: PlaybackAttemptId): Boolean = synchronized(lock) {
        active && requestedAttemptId == attemptId
    }

    private fun resetAudioPathLocked() {
        acceptedAudioData = false
        lastAudioSinkPositionUs = null
    }

    private fun resetVideoPathLocked() {
        renderedVideoPathReady = false
        firstFrameEmitted = false
        lastVideoEmissionElapsedMillis = null
    }

    private fun acceptsVideoOutputLocked(
        requestedAttemptId: PlaybackAttemptId,
        outputGeneration: Long,
    ): Boolean = active &&
        requestedAttemptId == attemptId &&
        outputGeneration == intendedVideoOutputGeneration &&
        intendedVideoOutputAttached

    private fun monotonicNowLocked(): Long {
        val observed = clock.elapsedRealtimeMillis().coerceAtLeast(0)
        lastObservedElapsedMillis = maxOf(
            elapsedFloorMillis,
            lastObservedElapsedMillis,
            observed,
        )
        return lastObservedElapsedMillis
    }

    private inner class EvidenceAnalyticsListener : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            recordVideoDecoderInitialized(attemptId, decoderName)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            recordAudioDecoderInitialized(attemptId, decoderName)
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            recordRenderedFirstFrame(attemptId, intendedVideoOutputGeneration)
        }

        override fun onVideoFrameProcessingOffset(
            eventTime: AnalyticsListener.EventTime,
            totalProcessingOffsetUs: Long,
            frameCount: Int,
        ) {
            recordProcessedVideoFrameBatch(
                requestedAttemptId = attemptId,
                frameCount = frameCount,
                outputGeneration = intendedVideoOutputGeneration,
            )
        }
    }

    private inner class EvidenceAudioSink(
        private val delegate: AudioSink,
    ) : ForwardingAudioSink(delegate) {
        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int,
        ): Boolean {
            val offeredByteCount = buffer.remaining()
            val fullyHandled = delegate.handleBuffer(
                buffer,
                presentationTimeUs,
                encodedAccessUnitCount,
            )
            val acceptedByteCount = (offeredByteCount - buffer.remaining()).coerceAtLeast(0)
            recordAudioBufferAccepted(attemptId, acceptedByteCount)
            return fullyHandled
        }

        override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
            val positionUs = delegate.getCurrentPositionUs(sourceEnded)
            recordAudioSinkPosition(attemptId, positionUs)
            return positionUs
        }

        override fun handleDiscontinuity() {
            resetAudioPath(attemptId)
            delegate.handleDiscontinuity()
        }

        override fun pause() {
            resetAudioPath(attemptId)
            delegate.pause()
        }

        override fun flush() {
            resetAudioPath(attemptId)
            delegate.flush()
        }

        override fun reset() {
            resetAudioPath(attemptId)
            delegate.reset()
        }

        override fun release() {
            cancel(attemptId)
            delegate.release()
        }
    }
}

private fun intervalReached(previous: Long?, current: Long, minimumInterval: Long): Boolean =
    previous == null || current - previous >= minimumInterval

private fun Long.nextSequenceOrNull(): Long? = if (this == Long.MAX_VALUE) null else this + 1

private fun String.normalizedDecoderNameOrNull(): String? {
    if (isBlank() || length > MAX_DECODER_NAME_LENGTH) return null
    return trim().lowercase(Locale.ROOT)
}

private fun MediaCodecInfo.implementationEvidence(): DecoderImplementationEvidence {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return when {
            isSoftwareOnly && isHardwareAccelerated -> DecoderImplementationEvidence.UNKNOWN
            isSoftwareOnly -> DecoderImplementationEvidence.SOFTWARE
            isHardwareAccelerated -> DecoderImplementationEvidence.HARDWARE
            else -> DecoderImplementationEvidence.UNKNOWN
        }
    }
    return conservativeSoftwareClassification(name.lowercase(Locale.ROOT))
}

private fun MutableMap<String, DecoderImplementationEvidence>.mergeConservatively(
    normalizedName: String,
    incoming: DecoderImplementationEvidence,
) {
    val current = this[normalizedName]
    this[normalizedName] = when {
        current == null -> incoming
        current == incoming -> current
        else -> DecoderImplementationEvidence.UNKNOWN
    }
}

private fun conservativeSoftwareClassification(
    normalizedName: String,
): DecoderImplementationEvidence = when {
    normalizedName.startsWith("omx.google.") ||
        normalizedName.startsWith("omx.ffmpeg.") ||
        normalizedName.startsWith("c2.android.") ||
        normalizedName.startsWith("c2.google.") ||
        (normalizedName.startsWith("omx.sec.") && ".sw." in normalizedName) ||
        normalizedName == "omx.qcom.video.decoder.hevcswvdec" ->
        DecoderImplementationEvidence.SOFTWARE

    else -> DecoderImplementationEvidence.UNKNOWN
}

private fun opaqueDecoderAlias(normalizedName: String): SanitizedDecoderName? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalizedName.toByteArray(Charsets.UTF_8))
    val alias = buildString(DECODER_ALIAS_PREFIX.length + DECODER_ALIAS_HEX_LENGTH) {
        append(DECODER_ALIAS_PREFIX)
        repeat(DECODER_ALIAS_BYTES) { index ->
            append((digest[index].toInt() and 0xff).toString(16).padStart(2, '0'))
        }
    }
    SanitizedDecoderName.fromAdapter(alias)
}.getOrNull()

private const val MAX_DECODER_NAME_LENGTH = 256
private const val DECODER_ALIAS_PREFIX = "decoder-"
private const val DECODER_ALIAS_BYTES = 8
private const val DECODER_ALIAS_HEX_LENGTH = DECODER_ALIAS_BYTES * 2
