package app.opah.tv.ui.views

import app.opah.tv.R
import app.opah.tv.ui.SettingsPage

internal enum class NativeDestination(
    val label: String,
    val iconRes: Int,
) {
    HOME("Home", R.drawable.ic_home),
    ACTIVITY("Activity", R.drawable.ic_review),
    CLIPS("Clips", R.drawable.ic_saved),
    SETTINGS("Settings", R.drawable.ic_settings),
}

internal enum class NativeActivityPage { NEW, REVIEWED, HISTORY, SEARCH, MOTION }

internal enum class NativeClipsPage { RECORDINGS, INCIDENTS }

internal enum class NativeServerPage { CONNECTION, PERFORMANCE, STORAGE }

internal data class NativeRoute(
    val destination: NativeDestination = NativeDestination.HOME,
    val settingsPage: SettingsPage = SettingsPage.MAIN,
) {
    init {
        require(destination == NativeDestination.SETTINGS || settingsPage == SettingsPage.MAIN)
    }

    val focusMemoryKey: String = when {
        destination != NativeDestination.SETTINGS -> destination.name
        else -> "SETTINGS:${settingsPage.name}"
    }
}

internal enum class NativeRowKind {
    ACTION,
    TOGGLE,
    CHOICE,
    ADJUSTMENT,
    READING,
    INFORMATION,
    VISUAL,
    DASHBOARD,
    THEME_PREVIEW,
    SECTION,
}

internal data class NativeMeterSegment(
    val amount: Double,
    val color: Int,
)

internal data class NativeDashboardMetric(
    val label: String,
    val value: String,
    val color: Int,
)

internal enum class NativeThumbnailKind {
    CAMERA,
    REVIEW,
    CLIP,
    SEARCH,
}

internal data class NativeThumbnailRequest(
    val kind: NativeThumbnailKind,
    val sourceId: String,
) {
    val cacheKey: String = "${kind.name}:$sourceId"
}

internal data class NativeRowModel(
    val key: String,
    val title: String,
    val description: String = "",
    val value: String = "",
    val kind: NativeRowKind = NativeRowKind.ACTION,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val thumbnail: NativeThumbnailRequest? = null,
    val indicatorColor: Int? = null,
    val meterSegments: List<NativeMeterSegment> = emptyList(),
    val dashboardMetrics: List<NativeDashboardMetric> = emptyList(),
    val previewAccent: Int? = null,
    val previewBackground: Int? = null,
    val secondaryActionKey: String? = null,
    val secondaryActionLabel: String = "",
) {
    val focusable: Boolean get() = enabled && kind in FOCUSABLE_ROW_KINDS
    val actionable: Boolean get() = enabled && kind in ACTIONABLE_ROW_KINDS

    private companion object {
        val FOCUSABLE_ROW_KINDS = setOf(
            NativeRowKind.ACTION,
            NativeRowKind.TOGGLE,
            NativeRowKind.CHOICE,
            NativeRowKind.ADJUSTMENT,
            NativeRowKind.READING,
        )
        val ACTIONABLE_ROW_KINDS = FOCUSABLE_ROW_KINDS
    }
}

internal enum class NativeBackAction {
    CLOSE_NAVIGATION,
    CLOSE_DETAIL,
    CLOSE_CONTENT_OVERLAY,
    OPEN_NAVIGATION,
    EXIT_APP,
}

internal fun nativeBackAction(
    navigationOpen: Boolean,
    inPlayback: Boolean,
    inContentOverlay: Boolean,
    route: NativeRoute,
): NativeBackAction = when {
    navigationOpen -> NativeBackAction.CLOSE_NAVIGATION
    inPlayback -> NativeBackAction.CLOSE_CONTENT_OVERLAY
    inContentOverlay -> NativeBackAction.CLOSE_CONTENT_OVERLAY
    route.destination == NativeDestination.SETTINGS && route.settingsPage != SettingsPage.MAIN ->
        NativeBackAction.CLOSE_DETAIL
    route.destination != NativeDestination.HOME -> NativeBackAction.OPEN_NAVIGATION
    else -> NativeBackAction.OPEN_NAVIGATION
}

internal fun shouldKeepFocusInContent(
    direction: Int,
    focusedInContent: Boolean,
    proposedInNavigation: Boolean,
): Boolean = focusedInContent && proposedInNavigation && (
    direction == android.view.View.FOCUS_UP || direction == android.view.View.FOCUS_DOWN
    )

internal fun stableNativeItemId(key: String): Long {
    var hash = -0x340d631b7bdddcdbL
    key.forEach { character ->
        hash = hash xor character.code.toLong()
        hash *= 0x100000001b3L
    }
    return hash
}
