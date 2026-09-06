package app.opah.tv.playback.compatibility

import java.util.ArrayList
import java.util.Collections

/**
 * Privacy-minimized diagnostics for the user-initiated compatibility test.
 *
 * The trace API intentionally has no route, stream, host, URI, exception, or free-form text
 * parameter. Adapters must classify failures before they cross this boundary. Timings are relative
 * durations rather than wall-clock or process-clock timestamps.
 */
class PlaybackCompatibilityAttemptTrace(
    private val maximumEntries: Int = DEFAULT_PLAYBACK_COMPATIBILITY_TRACE_ENTRIES,
) {
    private val lock = Any()
    private val entries = ArrayDeque<PlaybackCompatibilityTraceEvent>(maximumEntries)
    private var droppedEntryCount: Long = 0

    init {
        require(maximumEntries in 1..MAX_PLAYBACK_COMPATIBILITY_TRACE_ENTRIES) {
            "Playback compatibility trace capacity must be between 1 and " +
                MAX_PLAYBACK_COMPATIBILITY_TRACE_ENTRIES
        }
    }

    /**
     * Adds a sanitized event. Once full, the trace discards its oldest event so the most recent
     * terminal evidence remains available. The number of discarded events saturates at [Long.MAX_VALUE].
     */
    fun record(event: PlaybackCompatibilityTraceEvent) {
        synchronized(lock) {
            if (entries.size == maximumEntries) {
                entries.removeFirst()
                if (droppedEntryCount != Long.MAX_VALUE) droppedEntryCount += 1
            }
            entries.addLast(event)
        }
    }

    /** Returns an immutable point-in-time copy in recording order. */
    fun snapshot(): PlaybackCompatibilityAttemptReport = synchronized(lock) {
        PlaybackCompatibilityAttemptReport(
            entries = entries.toList(),
            droppedEntryCount = droppedEntryCount,
        )
    }

    /** Clears only this in-memory, privacy-minimized diagnostic trace. */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            droppedEntryCount = 0
        }
    }
}

const val DEFAULT_PLAYBACK_COMPATIBILITY_TRACE_ENTRIES: Int = 32
const val MAX_PLAYBACK_COMPATIBILITY_TRACE_ENTRIES: Int = 64

internal const val MAX_PLAYBACK_COMPATIBILITY_TRACE_ORDINAL: Int = 1_000_000
internal const val MAX_PLAYBACK_COMPATIBILITY_TRACE_DURATION_MILLIS: Long = 86_400_000
internal const val MAX_PLAYBACK_COMPATIBILITY_TRACE_PROGRESS_EVENTS: Int = 1_000_000
internal const val MAX_PLAYBACK_COMPATIBILITY_REPORT_LINE_CHARACTERS: Int = 512
internal const val MAX_PLAYBACK_COMPATIBILITY_REPORT_CHARACTERS: Int = 40_000

/** Opaque, trace-local attempt ordering. It carries no session or media identity. */
@JvmInline
value class PlaybackTraceAttemptOrdinal(val value: Int) {
    init {
        require(value in 1..MAX_PLAYBACK_COMPATIBILITY_TRACE_ORDINAL) {
            "Playback trace attempt ordinal is outside its bounded range"
        }
    }
}

/** Coarse resolution keeps the report useful without retaining exact source dimensions. */
enum class PlaybackTraceResolutionClass {
    UNKNOWN,
    SD_OR_LOWER,
    HD,
    FULL_HD,
    UHD,
    ABOVE_UHD,
}

/**
 * Candidate properties that are safe to report. Source identity is intentionally absent.
 */
data class PlaybackTraceCandidateDimensions(
    val videoCodec: VideoCodec,
    val audioCodec: AudioCodec?,
    val audioMode: AudioMode,
    val transportMode: TransportMode,
    val decoderMode: DecoderMode,
    val origin: CandidateOrigin,
    val resolutionClass: PlaybackTraceResolutionClass = PlaybackTraceResolutionClass.UNKNOWN,
) {
    init {
        require(audioMode != AudioMode.WITH_AUDIO || audioCodec != null) {
            "A trace candidate cannot enable absent audio"
        }
    }
}

enum class PlaybackTraceLifecycle {
    ATTEMPT_STARTED,
    FIRST_FRAME_OBSERVED,
    STABILITY_VERIFIED,
    ATTEMPT_FAILED,
    ATTEMPT_RELEASED,
    RELEASE_FAILED,
    ATTEMPT_CANCELLED,
}

/** Relative durations only; absolute timestamps never enter the trace. */
data class PlaybackTraceTiming(
    val attemptDurationMillis: Long,
    val firstFrameLatencyMillis: Long? = null,
    val stablePlaybackDurationMillis: Long? = null,
) {
    init {
        require(attemptDurationMillis in 0..MAX_PLAYBACK_COMPATIBILITY_TRACE_DURATION_MILLIS) {
            "Playback trace attempt duration is outside its bounded range"
        }
        require(
            firstFrameLatencyMillis == null ||
                firstFrameLatencyMillis in 0..attemptDurationMillis,
        ) { "First-frame latency must fit within the attempt duration" }
        require(
            stablePlaybackDurationMillis == null ||
                stablePlaybackDurationMillis in 0..attemptDurationMillis,
        ) { "Stable-playback duration must fit within the attempt duration" }
        require(
            firstFrameLatencyMillis == null ||
                stablePlaybackDurationMillis == null ||
                firstFrameLatencyMillis <= attemptDurationMillis - stablePlaybackDurationMillis,
        ) { "First-frame and stable-playback durations must fit within the attempt duration" }
    }

    companion object {
        val ZERO = PlaybackTraceTiming(attemptDurationMillis = 0)
    }
}

enum class PlaybackTraceDecoderState {
    NOT_OBSERVED,
    INITIALIZED,
    FAILED,
}

/** A typed decoder observation; it deliberately contains no decoder or exception name. */
data class PlaybackTraceDecoderEvidence(
    val state: PlaybackTraceDecoderState,
    val implementation: DecoderImplementationEvidence,
) {
    init {
        require(
            state != PlaybackTraceDecoderState.NOT_OBSERVED ||
                implementation == DecoderImplementationEvidence.UNKNOWN,
        ) { "Unobserved decoder evidence cannot claim an implementation" }
    }

    companion object {
        val NONE = PlaybackTraceDecoderEvidence(
            state = PlaybackTraceDecoderState.NOT_OBSERVED,
            implementation = DecoderImplementationEvidence.UNKNOWN,
        )
    }
}

enum class PlaybackTraceAudioRendererEvidence {
    NOT_REQUESTED,
    NOT_OBSERVED,
    READY,
    PROGRESSING,
    FAILED,
}

/**
 * Evidence stays separated by media responsibility so an audio-path failure is never reported as
 * video-decoder evidence (or vice versa).
 */
data class PlaybackTraceMediaEvidence(
    val videoDecoder: PlaybackTraceDecoderEvidence = PlaybackTraceDecoderEvidence.NONE,
    val audioDecoder: PlaybackTraceDecoderEvidence = PlaybackTraceDecoderEvidence.NONE,
    val audioRenderer: PlaybackTraceAudioRendererEvidence =
        PlaybackTraceAudioRendererEvidence.NOT_OBSERVED,
    val videoProgressEventCount: Int = 0,
    val audioProgressEventCount: Int = 0,
) {
    init {
        require(videoProgressEventCount in 0..MAX_PLAYBACK_COMPATIBILITY_TRACE_PROGRESS_EVENTS) {
            "Video progress event count is outside its bounded range"
        }
        require(audioProgressEventCount in 0..MAX_PLAYBACK_COMPATIBILITY_TRACE_PROGRESS_EVENTS) {
            "Audio progress event count is outside its bounded range"
        }
    }

    companion object {
        val NONE = PlaybackTraceMediaEvidence()
    }
}

/** One already-sanitized lifecycle observation. */
data class PlaybackCompatibilityTraceEvent(
    val attemptOrdinal: PlaybackTraceAttemptOrdinal,
    val candidate: PlaybackTraceCandidateDimensions,
    val lifecycle: PlaybackTraceLifecycle,
    val timing: PlaybackTraceTiming,
    val failure: ClassifiedPlaybackFailure? = null,
    val evidence: PlaybackTraceMediaEvidence = PlaybackTraceMediaEvidence.NONE,
) {
    init {
        when (lifecycle) {
            PlaybackTraceLifecycle.ATTEMPT_FAILED -> require(failure != null) {
                "A failed attempt must carry an allowlisted classified failure"
            }

            PlaybackTraceLifecycle.RELEASE_FAILED -> require(
                failure?.diagnosticCode == PlaybackDiagnosticCode.RELEASE_FAILED,
            ) { "A release failure must carry the allowlisted release-failed diagnostic" }

            else -> require(failure == null) {
                "Only failed lifecycle observations may carry a classified failure"
            }
        }

        if (lifecycle == PlaybackTraceLifecycle.ATTEMPT_STARTED) {
            require(timing == PlaybackTraceTiming.ZERO) {
                "An attempt-start observation cannot claim elapsed playback time"
            }
            require(evidence.videoDecoder == PlaybackTraceDecoderEvidence.NONE) {
                "An attempt-start observation cannot claim video-decoder evidence"
            }
            require(evidence.audioDecoder == PlaybackTraceDecoderEvidence.NONE) {
                "An attempt-start observation cannot claim audio-decoder evidence"
            }
            require(
                evidence.videoProgressEventCount == 0 && evidence.audioProgressEventCount == 0,
            ) { "An attempt-start observation cannot claim media progress" }
            val expectedAudioRenderer = if (candidate.audioMode == AudioMode.VIDEO_ONLY) {
                PlaybackTraceAudioRendererEvidence.NOT_REQUESTED
            } else {
                PlaybackTraceAudioRendererEvidence.NOT_OBSERVED
            }
            require(evidence.audioRenderer == expectedAudioRenderer) {
                "An attempt-start observation cannot claim audio-renderer evidence"
            }
        }
        if (lifecycle == PlaybackTraceLifecycle.FIRST_FRAME_OBSERVED) {
            require(timing.firstFrameLatencyMillis != null) {
                "A first-frame observation must carry its bounded relative latency"
            }
        }
        if (lifecycle == PlaybackTraceLifecycle.STABILITY_VERIFIED) {
            require(timing.firstFrameLatencyMillis != null) {
                "A stability observation must carry first-frame latency"
            }
            require((timing.stablePlaybackDurationMillis ?: 0) > 0) {
                "A stability observation must carry a positive stable-playback duration"
            }
        }
        if (candidate.audioMode == AudioMode.VIDEO_ONLY) {
            require(evidence.audioDecoder == PlaybackTraceDecoderEvidence.NONE) {
                "A video-only candidate cannot claim audio-decoder evidence"
            }
            require(evidence.audioRenderer == PlaybackTraceAudioRendererEvidence.NOT_REQUESTED) {
                "A video-only candidate must report audio as not requested"
            }
            require(evidence.audioProgressEventCount == 0) {
                "A video-only candidate cannot claim audio progress"
            }
        } else {
            require(evidence.audioRenderer != PlaybackTraceAudioRendererEvidence.NOT_REQUESTED) {
                "An audio-enabled candidate cannot report audio as not requested"
            }
        }
    }
}

/** Immutable, bounded report snapshot suitable for display or explicit user export. */
class PlaybackCompatibilityAttemptReport internal constructor(
    entries: List<PlaybackCompatibilityTraceEvent>,
    val droppedEntryCount: Long,
) {
    val entries: List<PlaybackCompatibilityTraceEvent> = Collections.unmodifiableList(
        ArrayList(entries),
    )

    init {
        require(this.entries.size <= MAX_PLAYBACK_COMPATIBILITY_TRACE_ENTRIES)
        require(droppedEntryCount >= 0)
    }

    /**
     * Renders stable, locale-independent, line-oriented text made exclusively from enums and
     * bounded nonnegative numbers. No free-form value is interpolated into the output.
     */
    fun renderSanitizedText(): String {
        val lines = ArrayList<String>(entries.size + 1)
        lines += "opah-playback-compatibility-report-v1 retained=${entries.size} " +
            "dropped=$droppedEntryCount"
        entries.forEach { event ->
            lines += event.renderSanitizedLine()
        }
        check(lines.all { it.length <= MAX_PLAYBACK_COMPATIBILITY_REPORT_LINE_CHARACTERS }) {
            "Playback compatibility report line exceeded its fixed bound"
        }
        return lines.joinToString(separator = "\n").also { report ->
            check(report.length <= MAX_PLAYBACK_COMPATIBILITY_REPORT_CHARACTERS) {
                "Playback compatibility report exceeded its fixed bound"
            }
        }
    }
}

private fun PlaybackCompatibilityTraceEvent.renderSanitizedLine(): String = buildString {
    append("attempt=")
    append(attemptOrdinal.value)
    append(" lifecycle=")
    append(lifecycle.name)
    append(" candidate[")
    append("video=")
    append(candidate.videoCodec.name)
    append(",audio=")
    append(candidate.audioCodec?.name ?: "NONE")
    append(",audio_mode=")
    append(candidate.audioMode.name)
    append(",transport=")
    append(candidate.transportMode.name)
    append(",decoder=")
    append(candidate.decoderMode.name)
    append(",origin=")
    append(candidate.origin.name)
    append(",resolution=")
    append(candidate.resolutionClass.name)
    append("] timing[")
    append("attempt_ms=")
    append(timing.attemptDurationMillis)
    append(",first_frame_ms=")
    append(timing.firstFrameLatencyMillis ?: "NONE")
    append(",stable_ms=")
    append(timing.stablePlaybackDurationMillis ?: "NONE")
    append("] failure[")
    val classifiedFailure = failure
    if (classifiedFailure == null) {
        append("NONE")
    } else {
        append("category=")
        append(classifiedFailure.category.name)
        append(",phase=")
        append(classifiedFailure.phase.name)
        append(",code=")
        append(classifiedFailure.diagnosticCode.name)
    }
    append("] evidence[")
    append("video_decoder=")
    append(evidence.videoDecoder.state.name)
    append('/')
    append(evidence.videoDecoder.implementation.name)
    append(",audio_decoder=")
    append(evidence.audioDecoder.state.name)
    append('/')
    append(evidence.audioDecoder.implementation.name)
    append(",audio_renderer=")
    append(evidence.audioRenderer.name)
    append(",video_progress=")
    append(evidence.videoProgressEventCount)
    append(",audio_progress=")
    append(evidence.audioProgressEventCount)
    append(']')
}
