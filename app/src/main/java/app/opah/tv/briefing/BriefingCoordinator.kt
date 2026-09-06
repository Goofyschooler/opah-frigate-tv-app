package app.opah.tv.briefing

import app.opah.tv.data.FrigateRepository
import app.opah.tv.data.ReviewDiscovery
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.playback.compatibility.CompatibilityIdentityDomain
import app.opah.tv.playback.compatibility.CompatibilityIdentityFactory
import app.opah.tv.privacy.RecognitionDisclosure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BriefingScopeFactory(
    private val identities: CompatibilityIdentityFactory,
) {
    fun derive(
        profileKey: String,
        audience: BriefingAudience,
        privacySchemaVersion: Int,
    ): BriefingScopeKey {
        require(profileKey.isNotBlank())
        require(privacySchemaVersion > 0)
        return BriefingScopeKey(
            identities.derive(
                CompatibilityIdentityDomain.BRIEFING_SCOPE,
                mapOf(
                    "profile" to profileKey,
                    "audience" to audience.name.lowercase(),
                    "privacy_schema" to privacySchemaVersion.toString(),
                ),
            ).value,
        )
    }
}

fun interface BriefingReviewSource {
    suspend fun search(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        query: ReviewSearchQuery,
        beforeBySeverity: Map<ReviewSeverity, Double>,
    ): ReviewDiscovery
}

class FrigateBriefingReviewSource(
    private val repository: FrigateRepository,
) : BriefingReviewSource {
    override suspend fun search(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        query: ReviewSearchQuery,
        beforeBySeverity: Map<ReviewSeverity, Double>,
    ): ReviewDiscovery = repository.searchReview(profile, allowedCameras, query, beforeBySeverity)
}

data class BriefingRefreshRequest(
    val profile: ConnectionProfile,
    val profileKey: String,
    val scopeKey: BriefingScopeKey,
    val allowedCameraIds: Set<String>,
    val privacySchemaVersion: Int,
    val privacyEpoch: Long,
    val recognitionDisclosure: RecognitionDisclosure,
    val nowEpochMillis: Long,
)

/**
 * Owns the bounded, process-death-safe Review query and acknowledgement boundary.
 * Server Review state is never changed here: local stable IDs and material versions are the truth.
 */
class BriefingCoordinator(
    private val store: BriefingStore,
    private val source: BriefingReviewSource,
) {
    private val operationMutex = Mutex()

    suspend fun refresh(request: BriefingRefreshRequest): BriefingSummary? = operationMutex.withLock {
        validate(request)
        val previous = store.read(request.profileKey, request.scopeKey)
        val lowerBound = queryLowerBound(request.nowEpochMillis, previous.metadata)
        val fetched = fetchBounded(request, lowerBound)
        val metadata = BriefingQueryMetadata(
            lastSuccessfulQueryAtEpochMillis = request.nowEpochMillis,
            lowerBoundEpochMillis = lowerBound,
            upperBoundEpochMillis = request.nowEpochMillis,
            capped = fetched.capped,
            privacySchemaVersion = request.privacySchemaVersion,
            privacyEpoch = request.privacyEpoch,
        )
        store.commitQuery(
            profileKey = request.profileKey,
            scopeKey = request.scopeKey,
            metadata = metadata,
            candidates = fetched.items.map { item ->
                val projected = item.forBriefingDisclosure(request.recognitionDisclosure)
                BriefingStoredCandidate(projected, BriefingSummarizer.contentVersion(projected))
            },
            retentionLowerBoundEpochMillis = (
                request.nowEpochMillis - BRIEFING_MAX_LOOKBACK_MILLIS -
                    BRIEFING_RETENTION_MARGIN_MILLIS
                ).coerceAtLeast(0L),
        )
        summarizeAuthorized(
            store.read(request.profileKey, request.scopeKey),
            request.allowedCameraIds,
            request.recognitionDisclosure,
        )
    }

    suspend fun readAuthorized(
        profileKey: String,
        scopeKey: BriefingScopeKey,
        allowedCameraIds: Set<String>,
        recognitionDisclosure: RecognitionDisclosure,
    ): BriefingSummary? = operationMutex.withLock {
        requireSafeRequestIdentity(profileKey, allowedCameraIds)
        summarizeAuthorized(store.read(profileKey, scopeKey), allowedCameraIds, recognitionDisclosure)
    }

    suspend fun acknowledgeVisible(
        profileKey: String,
        scopeKey: BriefingScopeKey,
        entries: Collection<BriefingEntry>,
        currentlyAllowedCameraIds: Set<String>,
        recognitionDisclosure: RecognitionDisclosure,
        reason: BriefingAcknowledgementReason,
        completedHighlightIds: Set<String> = emptySet(),
        nowEpochMillis: Long,
    ): Int = operationMutex.withLock {
        requireSafeRequestIdentity(profileKey, currentlyAllowedCameraIds)
        require(nowEpochMillis >= 0L)
        val currentCandidates = store.read(profileKey, scopeKey).candidates.asSequence()
            .filter { it.item.camera in currentlyAllowedCameraIds }
            .map { candidate ->
                val projected = candidate.item.forBriefingDisclosure(recognitionDisclosure)
                BriefingStoredCandidate(projected, BriefingSummarizer.contentVersion(projected))
            }
            .associateBy { it.item.id }
        val visible = entries.asSequence()
            .filter { entry ->
                val current = currentCandidates[entry.item.id]
                current != null &&
                    current.item.camera == entry.item.camera &&
                    current.contentVersion == entry.contentVersion
            }
            .distinctBy { it.item.id }
            .take(BRIEFING_MAX_PRESENTED)
            .toList()
        if (visible.isEmpty()) return@withLock 0
        val retainUntil = (nowEpochMillis + BRIEFING_MAX_LOOKBACK_MILLIS +
            BRIEFING_RETENTION_MARGIN_MILLIS).coerceAtLeast(nowEpochMillis)
        store.acknowledge(
            profileKey = profileKey,
            scopeKey = scopeKey,
            acknowledgements = visible.map { entry ->
                BriefingAcknowledgement(
                    reviewId = entry.item.id,
                    contentVersion = entry.contentVersion,
                    reason = reason,
                    acknowledgedAtEpochMillis = nowEpochMillis,
                    retainUntilEpochMillis = retainUntil,
                )
            },
            completedHighlights = visible.asSequence()
                .filter { it.item.id in completedHighlightIds }
                .map { entry -> CompletedBriefingHighlight(entry.item.id, nowEpochMillis, retainUntil) }
                .toList(),
            nowEpochMillis = nowEpochMillis,
        )
        visible.size
    }

    suspend fun dismissSummary(
        profileKey: String,
        scopeKey: BriefingScopeKey,
        targets: Collection<BriefingDismissalTarget>,
        currentlyAllowedCameraIds: Set<String>,
        recognitionDisclosure: RecognitionDisclosure,
        nowEpochMillis: Long,
    ): Int = operationMutex.withLock {
        requireSafeRequestIdentity(profileKey, currentlyAllowedCameraIds)
        require(targets.size <= BRIEFING_MAX_CANDIDATES)
        require(nowEpochMillis >= 0L)
        val currentCandidates = store.read(profileKey, scopeKey).candidates.asSequence()
            .filter { it.item.camera in currentlyAllowedCameraIds }
            .map { candidate ->
                val projected = candidate.item.forBriefingDisclosure(recognitionDisclosure)
                BriefingStoredCandidate(projected, BriefingSummarizer.contentVersion(projected))
            }
            .associateBy { it.item.id }
        val dismissible = targets.asSequence()
            .filter { target ->
                val current = currentCandidates[target.reviewId]
                current != null &&
                    current.item.camera == target.cameraId &&
                    current.contentVersion == target.contentVersion &&
                    current.item.containsBriefingRecognition() == target.containsRecognition
            }
            .distinctBy { it.reviewId }
            .toList()
        if (dismissible.isEmpty()) return@withLock 0
        val retainUntil = (nowEpochMillis + BRIEFING_MAX_LOOKBACK_MILLIS +
            BRIEFING_RETENTION_MARGIN_MILLIS).coerceAtLeast(nowEpochMillis)
        store.acknowledge(
            profileKey = profileKey,
            scopeKey = scopeKey,
            acknowledgements = dismissible.map { target ->
                BriefingAcknowledgement(
                    reviewId = target.reviewId,
                    contentVersion = target.contentVersion,
                    reason = BriefingAcknowledgementReason.DISMISSED,
                    acknowledgedAtEpochMillis = nowEpochMillis,
                    retainUntilEpochMillis = retainUntil,
                )
            },
            nowEpochMillis = nowEpochMillis,
        )
        dismissible.size
    }

    private suspend fun fetchBounded(
        request: BriefingRefreshRequest,
        lowerBoundEpochMillis: Long,
    ): FetchResult {
        if (request.allowedCameraIds.isEmpty()) return FetchResult(emptyList(), capped = false)
        val pending = ArrayDeque(
            listOf(
                SeverityCursor(ReviewSeverity.ALERT),
                SeverityCursor(ReviewSeverity.DETECTION),
                SeverityCursor(ReviewSeverity.SIGNIFICANT_MOTION),
            ),
        )
        val items = linkedMapOf<String, app.opah.tv.data.model.ReviewItem>()
        var calls = 0
        while (pending.isNotEmpty() && calls < MAX_QUERY_CALLS && items.size < BRIEFING_MAX_CANDIDATES) {
            val cursor = pending.removeFirst()
            val discovery = source.search(
                profile = request.profile,
                allowedCameras = request.allowedCameraIds,
                query = ReviewSearchQuery(
                    cameras = request.allowedCameraIds,
                    severity = cursor.severity,
                    after = lowerBoundEpochMillis / 1_000.0,
                    before = request.nowEpochMillis / 1_000.0,
                    limit = QUERY_PAGE_SIZE,
                ),
                beforeBySeverity = cursor.before?.let { mapOf(cursor.severity to it) }.orEmpty(),
            )
            calls += 1
            discovery.items.asSequence()
                .filter { item ->
                    item.camera in request.allowedCameraIds &&
                        item.startTime >= lowerBoundEpochMillis / 1_000.0 &&
                        item.startTime <= request.nowEpochMillis / 1_000.0
                }
                .forEach { item ->
                    if (items.size < BRIEFING_MAX_CANDIDATES || item.id in items) items[item.id] = item
                }
            discovery.nextBeforeBySeverity[cursor.severity]?.let { next ->
                if (next >= lowerBoundEpochMillis / 1_000.0) {
                    pending.addLast(SeverityCursor(cursor.severity, next))
                }
            }
        }
        return FetchResult(items.values.toList(), capped = pending.isNotEmpty())
    }

    private fun summarizeAuthorized(
        snapshot: BriefingStoreSnapshot,
        allowedCameraIds: Set<String>,
        recognitionDisclosure: RecognitionDisclosure,
    ): BriefingSummary? = BriefingSummarizer.summarize(
        candidates = snapshot.candidates.asSequence()
            .filter { it.item.camera in allowedCameraIds }
            .map { candidate ->
                val projected = candidate.item.forBriefingDisclosure(recognitionDisclosure)
                BriefingStoredCandidate(projected, BriefingSummarizer.contentVersion(projected))
            }
            .toList(),
        acknowledgements = snapshot.acknowledgements,
        capped = snapshot.metadata?.capped == true,
    )

    private fun queryLowerBound(nowEpochMillis: Long, metadata: BriefingQueryMetadata?): Long {
        val maximumLowerBound = (nowEpochMillis - BRIEFING_MAX_LOOKBACK_MILLIS).coerceAtLeast(0L)
        return if (metadata == null) {
            (nowEpochMillis - BRIEFING_INITIAL_LOOKBACK_MILLIS).coerceAtLeast(0L)
        } else {
            (metadata.lastSuccessfulQueryAtEpochMillis - BRIEFING_QUERY_OVERLAP_MILLIS)
                .coerceAtLeast(maximumLowerBound)
                .coerceAtMost(nowEpochMillis)
        }
    }

    private fun validate(request: BriefingRefreshRequest) {
        requireSafeRequestIdentity(request.profileKey, request.allowedCameraIds)
        require(request.privacySchemaVersion > 0)
        require(request.privacyEpoch >= 0L)
        require(request.nowEpochMillis >= 0L)
    }

    private fun requireSafeRequestIdentity(profileKey: String, cameras: Set<String>) {
        require(profileKey.isNotBlank() && profileKey.length <= 128 && profileKey.none(Char::isISOControl))
        require(cameras.size <= MAX_CAMERA_SCOPE)
        require(cameras.all { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) })
    }

    private data class SeverityCursor(val severity: ReviewSeverity, val before: Double? = null)
    private data class FetchResult(
        val items: List<app.opah.tv.data.model.ReviewItem>,
        val capped: Boolean,
    )

    private companion object {
        const val QUERY_PAGE_SIZE = 50
        const val MAX_QUERY_CALLS = 10
        const val MAX_CAMERA_SCOPE = 1_024
    }
}

private fun app.opah.tv.data.model.ReviewItem.forBriefingDisclosure(
    disclosure: RecognitionDisclosure,
): app.opah.tv.data.model.ReviewItem = when (disclosure) {
    RecognitionDisclosure.SHOW_ALL -> this
    RecognitionDisclosure.HIDE_NAMES -> copy(subLabels = emptyList(), summary = null, linkedEvents = emptyList())
    RecognitionDisclosure.HIDE_PLATES -> copy(summary = null, linkedEvents = emptyList())
    RecognitionDisclosure.HIDE_ALL -> copy(subLabels = emptyList(), summary = null, linkedEvents = emptyList())
}
