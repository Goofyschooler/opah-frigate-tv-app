package app.opah.tv.ui

import app.opah.tv.data.model.ReviewSeverity

/** Presentation selections only; restored identifiers still require current authorization. */
internal data class NativeBrowseSelections(
    val review: NativeReviewSelection? = null,
    val history: NativeHistorySelection? = null,
    val motion: NativeMotionSelection? = null,
    val search: NativeSearchSelection? = null,
    val searchDraft: NativeSearchSelection? = null,
    val profileKey: String? = null,
)

internal data class NativeReviewSelection(
    val severity: ReviewSeverity? = ReviewSeverity.ALERT,
    val cameraId: String? = null,
    val objectId: String? = null,
    val zoneId: String? = null,
    val timeRange: ReviewTimeRange = ReviewTimeRange.LAST_DAY,
    val reviewStatus: ReviewStatusFilter = ReviewStatusFilter.ALL,
)

internal data class NativeHistorySelection(
    val cameraId: String? = null,
    val hourStartSeconds: Double? = null,
)

internal data class NativeMotionSelection(
    val cameraId: String? = null,
    val regionIndex: Int = 4,
)

/** Deliberately excludes query text, recognized names, plates, and result content. */
internal data class NativeSearchSelection(
    val cameraId: String? = null,
    val objectId: String? = null,
    val zoneId: String? = null,
    val timeRange: ActivitySearchTimeRange = ActivitySearchTimeRange.ALL,
)

/** Bounds untrusted saved values without treating identifiers as access grants. */
internal fun NativeBrowseSelections.bounded(): NativeBrowseSelections = copy(
    profileKey = profileKey?.takeIf(PROFILE_KEY::matches),
    review = review?.let {
        it.copy(cameraId = browseId(it.cameraId), objectId = browseId(it.objectId), zoneId = browseId(it.zoneId))
    },
    history = history?.let {
        it.copy(
            cameraId = browseId(it.cameraId),
            hourStartSeconds = it.hourStartSeconds?.takeIf { time ->
                time.isFinite() && time in 0.0..32_503_680_000.0
            },
        )
    },
    motion = motion?.let {
        it.copy(cameraId = browseId(it.cameraId), regionIndex = it.regionIndex.takeIf { region -> region in 0..8 } ?: 4)
    },
    search = search?.bounded(),
    searchDraft = searchDraft?.bounded(),
)

private fun NativeSearchSelection.bounded() = copy(
    cameraId = browseId(cameraId), objectId = browseId(objectId), zoneId = browseId(zoneId),
)

private fun browseId(value: String?): String? = value?.takeIf {
    it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl)
}

private val PROFILE_KEY = Regex("h1:[0-9a-f]{64}")
