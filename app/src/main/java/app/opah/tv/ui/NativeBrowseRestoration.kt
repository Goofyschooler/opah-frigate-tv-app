package app.opah.tv.ui

import app.opah.tv.data.model.FrigateFeature

/** Kept in the ViewModel so reattaching an Activity never replaces active work with a Bundle. */
internal class NativeBrowseRestorationGate {
    private var attached = false
    var pending: NativeBrowseSelections? = null
        private set

    fun attach(saved: NativeBrowseSelections?) {
        if (attached) return
        attached = true
        pending = saved?.copy(searchDraft = null)
    }

    fun restore(access: NativeBrowseAccess?, apply: (NativeBrowseSelections) -> Unit): NativeBrowseRestoreResult {
        val saved = pending ?: return NativeBrowseRestoreResult.NO_PENDING
        if (access == null) return NativeBrowseRestoreResult.WAITING
        val valid = saved.authorizedFor(access)
        pending = null
        if (valid == null) return NativeBrowseRestoreResult.REJECTED_PROFILE
        apply(valid)
        return NativeBrowseRestoreResult.APPLIED
    }
}

internal enum class NativeBrowseRestoreResult { NO_PENDING, WAITING, APPLIED, REJECTED_PROFILE }

internal data class NativeBrowseAccess(
    val profileKey: String?,
    val reviewCameraIds: Set<String>,
    val historyCameraIds: Set<String>,
    val searchCameraIds: Set<String>,
    val features: Set<FrigateFeature>,
    val nowSeconds: Double,
)

/** This only validates presentation choices; it neither issues requests nor restores grants. */
internal fun NativeBrowseSelections.authorizedFor(access: NativeBrowseAccess): NativeBrowseSelections? {
    val saved = bounded()
    if (saved.profileKey == null || saved.profileKey != access.profileKey) return null
    fun NativeSearchSelection.safe(): NativeSearchSelection {
        val removedCamera = cameraId != null && cameraId !in access.searchCameraIds
        return copy(
            cameraId = cameraId?.takeIf(access.searchCameraIds::contains),
            objectId = objectId.takeUnless { removedCamera },
            zoneId = zoneId.takeUnless { removedCamera },
        )
    }
    val searchAvailable = FrigateFeature.SEMANTIC_SEARCH in access.features && access.searchCameraIds.isNotEmpty()
    return saved.copy(
        review = saved.review?.takeIf {
            FrigateFeature.REVIEW_ITEMS in access.features && access.reviewCameraIds.isNotEmpty()
        }?.let {
            val removedCamera = it.cameraId != null && it.cameraId !in access.reviewCameraIds
            it.copy(
                cameraId = it.cameraId?.takeIf(access.reviewCameraIds::contains),
                objectId = it.objectId.takeUnless { removedCamera },
                zoneId = it.zoneId.takeUnless { removedCamera },
            )
        },
        history = saved.history?.takeIf {
            FrigateFeature.RECORDING_HISTORY in access.features && access.historyCameraIds.isNotEmpty()
        }?.let { history ->
            val camera = history.cameraId?.takeIf(access.historyCameraIds::contains)
            history.copy(
                cameraId = camera,
                hourStartSeconds = history.hourStartSeconds?.takeIf { camera != null }
                    ?.let { hourStart(it.coerceAtMost(access.nowSeconds.coerceAtLeast(0.0))) },
            )
        },
        motion = saved.motion?.takeIf {
            FrigateFeature.MOTION_SEARCH in access.features && access.searchCameraIds.isNotEmpty()
        }?.let { motion ->
            val camera = motion.cameraId?.takeIf(access.searchCameraIds::contains)
            motion.copy(cameraId = camera, regionIndex = if (camera == null) 4 else motion.regionIndex)
        },
        search = saved.search?.takeIf { searchAvailable }?.safe(),
        searchDraft = saved.searchDraft?.takeIf { searchAvailable }?.safe(),
    )
}

internal fun ActivitySearchFilters.nativeSelection() = NativeSearchSelection(
    cameraId = cameraName, objectId = label, zoneId = zone, timeRange = timeRange,
)

internal fun NativeSearchSelection.filters() = ActivitySearchFilters(
    cameraName = cameraId, label = objectId, zone = zoneId, timeRange = timeRange,
)

/** The current slot's end grows with the clock; only its fixed start identifies the period. */
internal fun nativeHistoryRowKey(cameraName: String?, slot: HistorySlot): String {
    val camera = cameraName.orEmpty()
    return "activity:history:slot:${camera.length}:$camera:${slot.startTime.toLong()}"
}

internal fun Phase0UiState.captureNativeBrowseSelections(profileKey: String?) = NativeBrowseSelections(
    review = review.filters.let {
        NativeReviewSelection(it.severity, it.camera, it.label, it.zone, it.timeRange, it.reviewStatus)
    },
    history = NativeHistorySelection(history.cameraName, history.hourStartSeconds),
    motion = NativeMotionSelection(motionReview.cameraName, motionReview.regionIndex),
    search = activitySearch.filters.nativeSelection(),
    profileKey = profileKey,
)

/** Keep the original camera binding until authorization can discard its dependent filter IDs. */
internal fun Phase0UiState.captureAuthorizedNativeBrowseSelections(access: NativeBrowseAccess?): NativeBrowseSelections {
    if (access == null) return NativeBrowseSelections()
    return captureNativeBrowseSelections(access.profileKey).authorizedFor(access)
        ?: NativeBrowseSelections(profileKey = access.profileKey)
}

/** Called only on the first native attachment of a new ViewModel, before destination loading. */
internal fun Phase0UiState.withRestoredNativeBrowseSelections(saved: NativeBrowseSelections) = copy(
    review = saved.review?.let {
        ReviewBrowserState(filters = ReviewFilters(it.severity, it.cameraId, it.objectId, it.zoneId, it.timeRange, it.reviewStatus))
    } ?: review,
    history = saved.history?.let { HistoryBrowserState(cameraName = it.cameraId, hourStartSeconds = it.hourStartSeconds) } ?: history,
    motionReview = saved.motion?.let { MotionReviewUiState(cameraName = it.cameraId, regionIndex = it.regionIndex) } ?: motionReview,
    activitySearch = saved.search?.let { ActivitySearchState(filters = it.filters()) } ?: activitySearch,
)
