package app.opah.tv.ui

import app.opah.tv.data.model.RecordingSegment
import app.opah.tv.data.model.RecordingHourSummary
import app.opah.tv.data.model.MotionActivity
import app.opah.tv.data.model.SearchEvent
import kotlin.math.floor

data class HistoryBrowserState(
    val cameraName: String? = null,
    val hourStartSeconds: Double? = null,
    val segments: List<RecordingSegment> = emptyList(),
    val hourSummaries: List<RecordingHourSummary> = emptyList(),
    val motion: List<MotionActivity> = emptyList(),
    val summaryLoading: Boolean = false,
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val errorMessage: String? = null,
    val savingSlotStartTime: Double? = null,
    val savedSlotKeys: Set<String> = emptySet(),
    val savedMessage: String? = null,
)

enum class ActivitySearchTimeRange(val displayName: String, val seconds: Double?) {
    LAST_DAY("24 hours", 24 * 60 * 60.0),
    LAST_THREE_DAYS("3 days", 3 * 24 * 60 * 60.0),
    LAST_WEEK("7 days", 7 * 24 * 60 * 60.0),
    ALL("Any time", null),
}

data class ActivitySearchFilters(
    val cameraName: String? = null,
    val label: String? = null,
    val subLabel: String? = null,
    val zone: String? = null,
    val recognizedLicensePlate: String? = null,
    val timeRange: ActivitySearchTimeRange = ActivitySearchTimeRange.ALL,
)

data class ActivitySearchState(
    val query: String = "",
    val filters: ActivitySearchFilters = ActivitySearchFilters(),
    val results: List<SearchEvent> = emptyList(),
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val searchedOnce: Boolean = false,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val similarToEventId: String? = null,
    val similarToLabel: String? = null,
    val errorMessage: String? = null,
)

internal val ActivitySearchState.cameraName: String? get() = filters.cameraName

internal fun historySlotKey(cameraName: String, slot: HistorySlot): String =
    "$cameraName:${slot.startTime.toLong()}:${slot.endTime.toLong()}"

internal fun HistorySlot.motionLevel(activity: List<MotionActivity>): Double = activity
    .asSequence()
    .filter { it.startTime >= startTime && it.startTime < endTime }
    .sumOf(MotionActivity::motion)

data class HistorySlot(
    val startTime: Double,
    val endTime: Double,
    val playbackStartTime: Double?,
) {
    val available: Boolean get() = playbackStartTime != null
}

internal fun hourStart(epochSeconds: Double): Double =
    floor(epochSeconds / HISTORY_HOUR_SECONDS) * HISTORY_HOUR_SECONDS

internal fun historySlots(
    hourStartSeconds: Double,
    segments: List<RecordingSegment>,
    nowSeconds: Double,
): List<HistorySlot> = (0 until HISTORY_SLOT_COUNT).map { index ->
    val start = hourStartSeconds + index * HISTORY_SLOT_SECONDS
    val end = (start + HISTORY_SLOT_SECONDS).coerceAtMost(nowSeconds)
    val firstRecordingTime = segments.asSequence()
        .filter { it.endTime > start && it.startTime < end }
        .map { it.startTime.coerceAtLeast(start) }
        .minOrNull()
    HistorySlot(
        startTime = start,
        endTime = end.coerceAtLeast(start),
        playbackStartTime = firstRecordingTime.takeIf { end > start },
    )
}

internal fun canMoveHistoryForward(hourStartSeconds: Double, nowSeconds: Double): Boolean =
    hourStartSeconds + HISTORY_HOUR_SECONDS <= hourStart(nowSeconds)

internal const val HISTORY_HOUR_SECONDS = 60 * 60.0
private const val HISTORY_SLOT_SECONDS = 15 * 60.0
private const val HISTORY_SLOT_COUNT = 4
