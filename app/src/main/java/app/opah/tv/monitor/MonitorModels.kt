package app.opah.tv.monitor

import app.opah.tv.awareness.AwarenessReview

enum class MonitorPreset {
    FIXED,
    CALM,
    ACTIVE,
    PATROL,
}

enum class MonitorPhase {
    BASELINE,
    DEBOUNCING,
    PROMOTING,
    PROMOTED,
    LINGERING,
    MANUAL_HOLD,
    RECOVERING,
    EXITING,
}

enum class MonitorPresentationKind {
    LIVE,
    SNAPSHOT,
}

data class MonitorTimings(
    val debounceMillis: Long = 1_500L,
    val minimumDwellMillis: Long = 8_000L,
    val preemptionGuardMillis: Long = 2_000L,
    val lingerMillis: Long = 8_000L,
    val manualHoldMillis: Long = 30_000L,
    val networkGraceMillis: Long = 5_000L,
    val patrolIntervalMillis: Long = 20_000L,
) {
    init {
        listOf(
            debounceMillis,
            minimumDwellMillis,
            preemptionGuardMillis,
            lingerMillis,
            manualHoldMillis,
            networkGraceMillis,
            patrolIntervalMillis,
        ).forEach { require(it in 0L..MAX_MONITOR_TIMING_MILLIS) }
        require(preemptionGuardMillis <= minimumDwellMillis)
    }
}

data class MonitorConfiguration(
    val preset: MonitorPreset,
    val viewCameraIds: List<String>,
    val significantMotionEnabled: Boolean = false,
    val cameraPriorities: Map<String, Int> = emptyMap(),
    val zonePriorities: Map<String, Int> = emptyMap(),
    val objectPriorities: Map<String, Int> = emptyMap(),
    val timings: MonitorTimings = MonitorTimings(),
) {
    init {
        require(viewCameraIds.isNotEmpty() && viewCameraIds.size <= MAX_MONITOR_CAMERAS)
        require(viewCameraIds.distinct().size == viewCameraIds.size)
        (viewCameraIds + cameraPriorities.keys + zonePriorities.keys + objectPriorities.keys).forEach {
            require(it.isNotBlank() && it.length <= MAX_MONITOR_TEXT_CHARS && it.none(Char::isISOControl))
        }
        (cameraPriorities.values + zonePriorities.values + objectPriorities.values).forEach {
            require(it in 0..MAX_MONITOR_PRIORITY)
        }
    }
}

data class MonitorPromotion(
    val cameraId: String,
    val reviewId: String?,
    val rank: MonitorRank,
    val presentation: MonitorPresentationKind,
) {
    init {
        require(cameraId.isNotBlank() && cameraId.length <= MAX_MONITOR_TEXT_CHARS)
        require(reviewId == null || (reviewId.isNotBlank() && reviewId.length <= 256))
    }
}

@ConsistentCopyVisibility
data class MonitorRank internal constructor(
    val severity: Int,
    val threatLevel: Int,
    val explicitPriority: Int,
    val startEpochMillis: Long,
    val viewOrder: Int,
    val stableCameraId: String,
) : Comparable<MonitorRank> {
    override fun compareTo(other: MonitorRank): Int =
        compareValuesBy(
            this,
            other,
            MonitorRank::severity,
            MonitorRank::threatLevel,
            MonitorRank::explicitPriority,
            MonitorRank::startEpochMillis,
        ).takeIf { it != 0 }
            ?: other.viewOrder.compareTo(viewOrder).takeIf { it != 0 }
            ?: other.stableCameraId.compareTo(stableCameraId)
}

data class MonitorState(
    val phase: MonitorPhase = MonitorPhase.BASELINE,
    val privacyEpoch: Long,
    val promotion: MonitorPromotion? = null,
    val pending: MonitorPromotion? = null,
    val phaseEnteredAtEpochMillis: Long = 0L,
    val promotedAtEpochMillis: Long? = null,
    val deadlineEpochMillis: Long? = null,
    val networkLostAtEpochMillis: Long? = null,
    val liveReleasedForNetworkLoss: Boolean = false,
    val patrolCameraId: String? = null,
    val patrolDeadlineEpochMillis: Long? = null,
) {
    init {
        require(privacyEpoch >= 0L)
        require(phaseEnteredAtEpochMillis >= 0L)
        require(promotedAtEpochMillis == null || promotedAtEpochMillis >= 0L)
        require(deadlineEpochMillis == null || deadlineEpochMillis >= 0L)
        require(networkLostAtEpochMillis == null || networkLostAtEpochMillis >= 0L)
        require(patrolDeadlineEpochMillis == null || patrolDeadlineEpochMillis >= 0L)
    }
}

sealed interface MonitorAction {
    data object Tick : MonitorAction
    data class ManualSelect(val cameraId: String) : MonitorAction
    data object PlaybackReady : MonitorAction
    data object PlaybackDegradedToSnapshot : MonitorAction
    data object PlaybackFailed : MonitorAction
    data object Exit : MonitorAction
}

data class MonitorInput(
    val configuration: MonitorConfiguration,
    val visibleCameraIds: Set<String>,
    val privacyEpoch: Long,
    val reviews: Collection<AwarenessReview>,
    val networkAvailable: Boolean,
    val nowEpochMillis: Long,
    val action: MonitorAction = MonitorAction.Tick,
) {
    init {
        require(privacyEpoch >= 0L)
        require(nowEpochMillis >= 0L)
        require(visibleCameraIds.size <= MAX_MONITOR_CAMERAS)
        require(reviews.size <= MAX_MONITOR_REVIEWS)
    }
}

sealed interface MonitorCommand {
    data class BeginPromotion(val promotion: MonitorPromotion) : MonitorCommand
    data class ReleasePromotion(val cameraId: String) : MonitorCommand
    data object ShowBaseline : MonitorCommand
    data object ShowConnectionProblem : MonitorCommand
    data class ShowPatrolCamera(val cameraId: String) : MonitorCommand
    data object EndSession : MonitorCommand
}

data class MonitorTransition(
    val state: MonitorState,
    val commands: List<MonitorCommand> = emptyList(),
)

internal const val MAX_MONITOR_CAMERAS = 64
internal const val MAX_MONITOR_REVIEWS = 2_048
internal const val MAX_MONITOR_TEXT_CHARS = 256
internal const val MAX_MONITOR_PRIORITY = 1_000
internal const val MAX_MONITOR_TIMING_MILLIS = 24L * 60L * 60L * 1_000L
