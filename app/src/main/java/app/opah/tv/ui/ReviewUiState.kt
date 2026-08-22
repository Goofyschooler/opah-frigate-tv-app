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
)

internal fun ReviewBrowserState.canSaveClip(item: ReviewItem): Boolean =
    savingClipItemId == null &&
        item.recordingAvailable != false &&
        item.id !in savedClipItemIds

internal fun ReviewBrowserState.afterClipSaved(itemId: String): ReviewBrowserState = copy(
    savingClipItemId = null,
    savedClipItemIds = savedClipItemIds + itemId,
    savedClipItemId = itemId,
    savedClipMessage = "Recording saved in Frigate",
)

internal fun List<ReviewItem>.withReviewStatus(
    reviewId: String,
    reviewed: Boolean,
): List<ReviewItem> = map { item ->
    if (item.id == reviewId) item.copy(hasBeenReviewed = reviewed) else item
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
