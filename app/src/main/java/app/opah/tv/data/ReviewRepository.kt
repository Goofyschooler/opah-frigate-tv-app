package app.opah.tv.data

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.RecordingSegment
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewCounts
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.network.FrigateGateway
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.TimeZone

data class ReviewDiscovery(
    val items: List<ReviewItem>,
    val warnings: List<String> = emptyList(),
    val nextBeforeBySeverity: Map<app.opah.tv.data.model.ReviewSeverity, Double> = emptyMap(),
)

/** Loads Frigate Review data and resolves whether its recording windows still exist. */
class ReviewRepository(
    private val gateway: FrigateGateway,
    private val parsers: FrigateJsonParsers,
) {
    suspend fun recent(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        limit: Int = DEFAULT_FETCH_LIMIT,
    ): ReviewDiscovery = coroutineScope {
        val items = runCatching {
            load(
                profile = profile,
                allowedCameras = allowedCameras,
                query = ReviewSearchQuery(
                    cameras = allowedCameras,
                    after = (System.currentTimeMillis() / 1000.0) - RECENT_WINDOW_SECONDS,
                    limit = limit,
                ),
            )
        }.getOrElse {
            return@coroutineScope ReviewDiscovery(
                items = emptyList(),
                warnings = listOf("Recent Review items unavailable."),
            )
        }
        if (items.isEmpty()) return@coroutineScope ReviewDiscovery(emptyList())

        val warnings = mutableListOf<String>()

        val now = System.currentTimeMillis() / 1000.0
        val recordingResults = items.groupBy(ReviewItem::camera).map { (camera, cameraItems) ->
            async {
                val after = (cameraItems.minOf(ReviewItem::startTime) - RECORDING_PADDING_SECONDS)
                    .coerceAtLeast(0.0)
                val before = cameraItems.maxOf { it.endTime ?: now } + RECORDING_PADDING_SECONDS
                camera to runCatching {
                    parsers.parseRecordingSegments(gateway.getRecordings(profile, camera, after, before))
                }
            }
        }.map { it.await() }

        recordingResults.forEach { (camera, result) ->
            if (result.isFailure) {
                warnings += "Recording availability unavailable for ${camera.replace('_', ' ')}."
            }
        }
        val recordingsByCamera: Map<String, List<RecordingSegment>?> =
            recordingResults.associate { (camera, result) -> camera to result.getOrNull() }
        val resolvedItems = items.map { item ->
            val segments = recordingsByCamera[item.camera]
            val windowStart = (item.startTime - RECORDING_PADDING_SECONDS).coerceAtLeast(0.0)
            val windowEnd = (item.endTime ?: now) + RECORDING_PADDING_SECONDS
            item.copy(
                recordingAvailable = segments?.any { segment ->
                    segment.startTime <= windowEnd && segment.endTime >= windowStart
                },
            )
        }
        ReviewDiscovery(resolvedItems, warnings)
    }

    suspend fun search(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        query: ReviewSearchQuery,
        beforeBySeverity: Map<app.opah.tv.data.model.ReviewSeverity, Double> = emptyMap(),
    ): ReviewDiscovery = coroutineScope {
        if (allowedCameras.isEmpty()) return@coroutineScope ReviewDiscovery(emptyList())
        val severities = query.severity?.let(::listOf)
            ?: listOf(
                app.opah.tv.data.model.ReviewSeverity.ALERT,
                app.opah.tv.data.model.ReviewSeverity.DETECTION,
            )
        val perSeverityLimit = if (query.severity == null) {
            (query.limit / severities.size).coerceAtLeast(1)
        } else {
            query.limit
        }
        val batches = severities.map { severity ->
            async {
                severity to load(
                    profile,
                    allowedCameras,
                    query.copy(
                        severity = severity,
                        before = beforeBySeverity[severity] ?: query.before,
                        limit = perSeverityLimit,
                    ),
                )
            }
        }.map { it.await() }
        val next = batches.mapNotNull { (severity, items) ->
            items.minOfOrNull(ReviewItem::startTime)
                ?.takeIf { items.size >= perSeverityLimit }
                ?.let { severity to (it - 0.001) }
        }.toMap()
        ReviewDiscovery(
            items = batches.flatMap { it.second }.distinctBy(ReviewItem::id)
                .sortedByDescending(ReviewItem::startTime),
            nextBeforeBySeverity = next,
        )
    }

    suspend fun counts(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
    ): ReviewCounts {
        if (allowedCameras.isEmpty()) return ReviewCounts()
        return parsers.parseReviewCounts(
            gateway.getReviewSummary(profile, allowedCameras, TimeZone.getDefault().id),
        )
    }

    suspend fun enrich(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        item: ReviewItem,
    ): ReviewItem {
        if (item.camera !in allowedCameras || item.detectionIds.isEmpty()) return item
        val linked = item.detectionIds.chunked(MAX_LINKED_EVENT_IDS).flatMap { ids ->
            parsers.parseSearchEvents(gateway.getEventsByIds(profile, ids.toSet()))
        }.filter { it.camera in allowedCameras }
        return item.copy(linkedEvents = linked)
    }

    suspend fun recordingAvailable(
        profile: ConnectionProfile,
        item: ReviewItem,
    ): Result<Boolean> = runCatching {
        val now = System.currentTimeMillis() / 1000.0
        val windowStart = (item.startTime - RECORDING_PADDING_SECONDS).coerceAtLeast(0.0)
        val windowEnd = (item.endTime ?: now) + RECORDING_PADDING_SECONDS
        parsers.parseRecordingSegments(
            gateway.getRecordings(profile, item.camera, windowStart, windowEnd),
        ).any { segment -> segment.startTime <= windowEnd && segment.endTime >= windowStart }
    }

    fun playbackUrl(profile: ConnectionProfile, item: ReviewItem): String =
        gateway.reviewPlaybackUrl(profile, item)

    suspend fun setReviewed(profile: ConnectionProfile, item: ReviewItem, reviewed: Boolean) {
        gateway.setReviewsViewed(profile, setOf(item.id), reviewed = reviewed)
    }

    suspend fun setReviewed(
        profile: ConnectionProfile,
        items: Collection<ReviewItem>,
        reviewed: Boolean,
    ) {
        val ids = items.map(ReviewItem::id).filter(String::isNotBlank).toSet()
        if (ids.isNotEmpty()) gateway.setReviewsViewed(profile, ids, reviewed = reviewed)
    }

    private suspend fun load(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        query: ReviewSearchQuery,
    ): List<ReviewItem> {
        val requestedCameras = query.cameras.intersect(allowedCameras)
        if (requestedCameras.isEmpty()) return emptyList()
        val constrained = query.copy(cameras = requestedCameras)
        return parsers.parseReviewItems(gateway.getReview(profile, constrained))
            .filter { it.camera in requestedCameras }
    }

    private companion object {
        const val DEFAULT_FETCH_LIMIT = 50
        const val RECORDING_PADDING_SECONDS = 8.0
        const val RECENT_WINDOW_SECONDS = 24 * 60 * 60.0
        const val MAX_LINKED_EVENT_IDS = 50
    }
}
