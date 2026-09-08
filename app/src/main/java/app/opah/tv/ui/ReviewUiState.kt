package app.opah.tv.ui

import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewCounts
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.model.ReviewSeverity

enum class ReviewTimeRange(
    val displayName: String,
    val seconds: Double,
) {
    LAST_DAY("24 hours", 24 * 60 * 60.0),
    LAST_THREE_DAYS("3 days", 3 * 24 * 60 * 60.0),
    LAST_WEEK("7 days", 7 * 24 * 60 * 60.0),
}

data class ReviewFilters(
    val severity: ReviewSeverity? = ReviewSeverity.ALERT,
    val camera: String? = null,
    val label: String? = null,
    val zone: String? = null,
    val timeRange: ReviewTimeRange = ReviewTimeRange.LAST_DAY,
    val reviewStatus: ReviewStatusFilter = ReviewStatusFilter.ALL,
)

enum class ReviewStatusFilter(
    val displayName: String,
    val apiValue: Boolean?,
) {
    ALL("All", null),
    NOT_REVIEWED("Not reviewed", false),
    REVIEWED("Reviewed", true),
}

internal fun ReviewFilters.clearDetails(): ReviewFilters = ReviewFilters(severity = severity)

internal fun ReviewFilters.activeDetailCount(): Int = listOfNotNull(
    camera,
    label,
    zone,
).size +
    (if (timeRange != ReviewTimeRange.LAST_DAY) 1 else 0) +
    (if (reviewStatus != ReviewStatusFilter.ALL) 1 else 0)

enum class ReviewRecordingState {
    IDLE,
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}

internal fun reviewItemFocusKey(itemId: String): String = "review:item:$itemId"

data class ReviewBrowserState(
    val filters: ReviewFilters = ReviewFilters(),
    val items: List<ReviewItem> = emptyList(),
    val knownLabels: Set<String> = emptySet(),
    val knownZones: Set<String> = emptySet(),
    val counts: ReviewCounts = ReviewCounts(),
    val countsLoading: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val nextBeforeBySeverity: Map<ReviewSeverity, Double> = emptyMap(),
    val loadedOnce: Boolean = false,
    val errorMessage: String? = null,
    val selectedItemId: String? = null,
    val recordingState: ReviewRecordingState = ReviewRecordingState.IDLE,
    val detailErrorMessage: String? = null,
    val detailLoading: Boolean = false,
    val markingReviewedItemId: String? = null,
    val markingAllReviewed: Boolean = false,
    val savingClipItemId: String? = null,
    val savedClipItemIds: Set<String> = emptySet(),
    val savedClipItemId: String? = null,
    val savedClipMessage: String? = null,
    val queueItemIds: List<String> = emptyList(),
    val queueIndex: Int = 0,
    val queueActive: Boolean = false,
    val queueCompleted: Boolean = false,
    val playbackItem: ReviewItem? = null,
    val advancingPlayback: Boolean = false,
    val playbackNavigationMessage: String? = null,
)

data class NextActivityControlState(
    val label: String,
    val enabled: Boolean,
)

data class ReviewPlaybackContext(
    val itemIds: List<String>,
    val queue: Boolean,
)

internal fun reviewPlaybackContext(
    itemId: String,
    review: ReviewBrowserState,
    homeItems: List<ReviewItem>,
    useHomeActivityContext: Boolean,
): ReviewPlaybackContext {
    val queue = !useHomeActivityContext && review.queueActive && itemId in review.queueItemIds
    val itemIds = when {
        useHomeActivityContext -> homeItems.map(ReviewItem::id)
        queue -> review.queueItemIds
        review.items.any { it.id == itemId } -> review.items.map(ReviewItem::id)
        else -> homeItems.map(ReviewItem::id)
    }.takeIf { itemId in it } ?: listOf(itemId)
    return ReviewPlaybackContext(itemIds = itemIds, queue = queue)
}

internal fun nextReviewPlaybackItem(
    currentItemId: String?,
    contextItemIds: List<String>,
    availableItems: List<ReviewItem>,
): ReviewItem? {
    val currentIndex = contextItemIds.indexOf(currentItemId)
    if (currentIndex < 0) return null
    val itemsById = availableItems.associateBy(ReviewItem::id)
    return contextItemIds.asSequence()
        .drop(currentIndex + 1)
        .mapNotNull(itemsById::get)
        .firstOrNull { it.recordingAvailable != false }
}

internal fun nextActivityControlState(
    hasContext: Boolean,
    hasNextActivity: Boolean,
    queueContext: Boolean,
    loading: Boolean,
): NextActivityControlState? {
    if (!hasContext) return null
    return when {
        loading -> NextActivityControlState("Opening next activity", enabled = false)
        hasNextActivity -> NextActivityControlState("Next activity", enabled = true)
        queueContext -> NextActivityControlState("Caught up", enabled = false)
        else -> NextActivityControlState("No next activity", enabled = false)
    }
}

internal fun ReviewBrowserState.canSaveClip(item: ReviewItem): Boolean =
    savingClipItemId == null &&
        item.recordingAvailable != false &&
        item.id !in savedClipItemIds

internal fun ReviewBrowserState.unreviewedShownActivity(): List<ReviewItem> =
    items.filterNot(ReviewItem::hasBeenReviewed)

internal fun ReviewBrowserState.afterClipSaved(itemId: String): ReviewBrowserState = copy(
    savingClipItemId = null,
    savedClipItemIds = savedClipItemIds + itemId,
    savedClipItemId = itemId,
    savedClipMessage = "Recording saved in Frigate",
)

internal fun ReviewCounts.unreviewedFor(severity: ReviewSeverity?): Int = when (severity) {
    ReviewSeverity.ALERT -> unreviewedAlerts
    ReviewSeverity.DETECTION -> unreviewedDetections
    ReviewSeverity.SIGNIFICANT_MOTION,
    ReviewSeverity.UNKNOWN,
    -> 0
    null -> unreviewedTotal
}

internal fun List<ReviewItem>.withReviewStatus(
    reviewId: String,
    reviewed: Boolean,
): List<ReviewItem> = map { item ->
    if (item.id == reviewId) item.copy(hasBeenReviewed = reviewed) else item
}

internal fun List<ReviewItem>.withReviewStatuses(
    reviewedById: Map<String, Boolean>,
): List<ReviewItem> = map { item ->
    reviewedById[item.id]?.let { reviewed -> item.copy(hasBeenReviewed = reviewed) } ?: item
}

internal fun List<ReviewItem>.afterReviewStatusChanged(
    reviewId: String,
    reviewed: Boolean,
    reviewStatus: ReviewStatusFilter,
): List<ReviewItem> = if (reviewStatus.apiValue != null && reviewStatus.apiValue != reviewed) {
    filterNot { it.id == reviewId }
} else {
    withReviewStatus(reviewId, reviewed)
}

internal fun ReviewFilters.toSearchQuery(
    allowedCameras: Set<String>,
    nowSeconds: Double,
    beforeSeconds: Double = nowSeconds,
    limit: Int = REVIEW_PAGE_SIZE,
): ReviewSearchQuery {
    val requestedCameras = camera?.let(::setOf) ?: allowedCameras
    return ReviewSearchQuery(
        cameras = requestedCameras.intersect(allowedCameras),
        severity = severity,
        label = label,
        zone = zone,
        reviewed = reviewStatus.apiValue,
        after = (nowSeconds - timeRange.seconds).coerceAtLeast(0.0),
        before = beforeSeconds,
        limit = limit,
    )
}

internal const val REVIEW_PAGE_SIZE = 48
