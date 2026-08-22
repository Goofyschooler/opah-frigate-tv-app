package app.opah.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.PictureInPictureRequest
import app.opah.tv.R
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.playback.PlaybackKind

internal enum class AppDestination(val label: String, val iconRes: Int) {
    HOME("Home", R.drawable.ic_home),
    CAMERAS("Cameras", R.drawable.ic_videocam),
    BIRDSEYE("Birdseye", R.drawable.ic_birdseye),
    ACTIVITY("Activity", R.drawable.ic_review),
    SAVED("Saved", R.drawable.ic_saved),
    SETTINGS("Settings", R.drawable.ic_settings),
}

internal fun navigationAccessibilityLabel(
    destination: AppDestination,
    updateAvailable: Boolean,
    unreviewedActivityCount: Int = 0,
): String = when {
    destination == AppDestination.SETTINGS && updateAvailable -> "Settings, update available"
    destination == AppDestination.ACTIVITY && unreviewedActivityCount > 0 ->
        "Activity, $unreviewedActivityCount not reviewed"
    else -> destination.label
}

internal fun compactActivityCount(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

internal enum class RootSurface { PLAYBACK, CAMERA_GROUP, CONNECTED, LOADING, RECOVERY, SETUP }

internal data class CameraNeighbors(
    val previous: Camera?,
    val next: Camera?,
)

internal fun cameraNeighbors(cameras: List<Camera>, currentCameraName: String?): CameraNeighbors {
    val currentIndex = cameras.indexOfFirst { it.name == currentCameraName }
    if (currentIndex < 0 || cameras.size < 2) return CameraNeighbors(null, null)
    return CameraNeighbors(
        previous = cameras.wrappedAt(currentIndex - 1),
        next = cameras.wrappedAt(currentIndex + 1),
    )
}

internal fun rootSurface(
    hasPlayback: Boolean,
    hasConnectedContent: Boolean,
    loading: Boolean,
    connectionWorkInProgress: Boolean = false,
    savedSessionRecoveryAvailable: Boolean = false,
    hasCameraGroupView: Boolean = false,
): RootSurface = when {
    hasPlayback -> RootSurface.PLAYBACK
    hasCameraGroupView -> RootSurface.CAMERA_GROUP
    hasConnectedContent -> RootSurface.CONNECTED
    connectionWorkInProgress -> RootSurface.SETUP
    loading -> RootSurface.LOADING
    savedSessionRecoveryAvailable -> RootSurface.RECOVERY
    else -> RootSurface.SETUP
}

@Composable
fun OpahApp(
    viewModel: Phase0ViewModel,
    pictureInPictureAvailable: Boolean = false,
    pictureInPictureActive: Boolean = false,
    onEnterPictureInPicture: (PictureInPictureRequest) -> Boolean = { false },
    onFullyDrawn: () -> Unit = {},
    onExitRequested: () -> Unit = {},
    onInstallUpdate: (String) -> Unit = {},
    initialDestinationName: String? = null,
    initialSettingsPageName: String? = null,
    initialInformationTabName: String? = null,
    initialActivityPageName: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OpahTheme(state.settings.appearanceMode, state.settings.customThemeColors) {
        val normalizedInitialDestination = when (initialDestinationName) {
            "REVIEW" -> AppDestination.ACTIVITY.name
            "INFORMATION", "ABOUT" -> AppDestination.SETTINGS.name
            else -> initialDestinationName ?: AppDestination.HOME.name
        }
        val normalizedInitialSettingsPage = when {
            initialDestinationName == "INFORMATION" -> SettingsPage.SYSTEM.name
            initialDestinationName == "ABOUT" -> SettingsPage.ABOUT.name
            initialSettingsPageName == "DIAGNOSTICS" -> SettingsPage.ADVANCED.name
            else -> initialSettingsPageName ?: SettingsPage.MAIN.name
        }
        var destinationName by rememberSaveable(normalizedInitialDestination) {
            mutableStateOf(normalizedInitialDestination)
        }
        var returnDestinationName by rememberSaveable { mutableStateOf(AppDestination.HOME.name) }
        var restoreFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
        var playbackReturnFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
        var cameraGroupOriginFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
        val normalizedInitialActivityPage = initialActivityPageName
            ?.let { runCatching { ActivityPage.valueOf(it).name }.getOrNull() }
            ?: ActivityPage.RECENT.name
        var activityPageName by rememberSaveable(normalizedInitialActivityPage) {
            mutableStateOf(normalizedInitialActivityPage)
        }
        var activityBackDestinationName by rememberSaveable { mutableStateOf(AppDestination.HOME.name) }
        var activityBackFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
        var settingsPageName by rememberSaveable(normalizedInitialSettingsPage) {
            mutableStateOf(normalizedInitialSettingsPage)
        }
        var settingsReturnPageName by rememberSaveable { mutableStateOf<String?>(null) }
        var lastHomeFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
        val homeCameraFocusRequester = remember { FocusRequester() }
        val settingsMainFocusRequester = remember { FocusRequester() }
        val settingsDetailFocusRequester = remember { FocusRequester() }
        val destination = runCatching { AppDestination.valueOf(destinationName) }
            .getOrDefault(AppDestination.HOME)
        val settingsPage = runCatching { SettingsPage.valueOf(settingsPageName) }
            .getOrDefault(SettingsPage.MAIN)
        val activityPage = runCatching { ActivityPage.valueOf(activityPageName) }
            .getOrDefault(ActivityPage.RECENT)
        val playback = state.playback
        val surface = rootSurface(
            hasPlayback = playback != null,
            hasConnectedContent = state.activeProfile != null && state.snapshot != null,
            loading = state.loading,
            connectionWorkInProgress = state.connectionWorkInProgress,
            savedSessionRecoveryAvailable = state.savedSessionRecoveryAvailable,
            hasCameraGroupView = state.cameraGroupView != null,
        )

        LaunchedEffect(surface) {
            if (
                surface == RootSurface.CONNECTED ||
                surface == RootSurface.CAMERA_GROUP ||
                surface == RootSurface.SETUP
            ) {
                withFrameNanos { }
                onFullyDrawn()
            }
        }

        LaunchedEffect(surface, destination, settingsPage) {
            if (surface == RootSurface.CONNECTED) {
                playbackReturnFocusKey?.let { focusKey ->
                    restoreFocusKey = focusKey
                    playbackReturnFocusKey = null
                }
            }
            if (surface != RootSurface.CONNECTED || destination != AppDestination.SETTINGS) return@LaunchedEffect
            withFrameNanos { }
            if (settingsPage != SettingsPage.MAIN) {
                settingsDetailFocusRequester.requestFocus()
            }
        }

        when (surface) {
            RootSurface.PLAYBACK -> {
                val playbackRequest = requireNotNull(playback)
                val cameras = state.snapshot?.cameras.orEmpty()
                val (previous, next) = cameraNeighbors(cameras, state.activeCameraName)
                val activityItem = playbackRequest.activityItemId?.let { activityId ->
                    state.review.items.firstOrNull { it.id == activityId }
                        ?: state.snapshot?.recentReviewItems?.firstOrNull { it.id == activityId }
                }
                val historySaveKey = playbackRequest.recordingSaveKey
                PlaybackScreen(
                    request = playbackRequest,
                    onBack = {
                        destinationName = returnDestinationName
                        restoreFocusKey = playbackReturnFocusKey
                        playbackReturnFocusKey = null
                        viewModel.closePlayback()
                    },
                    onSessionExpired = viewModel::sessionExpired,
                    preferRtpTcp = state.settings.preferRtpTcp,
                    startMuted = state.settings.startLiveMuted,
                    diagnosticsAvailable = state.settings.diagnosticsEnabled,
                    pictureInPictureAvailable = pictureInPictureAvailable,
                    pictureInPictureActive = pictureInPictureActive,
                    onEnterPictureInPicture = onEnterPictureInPicture,
                    previousCameraName = previous?.displayName,
                    onPrevious = previous?.let { camera -> { viewModel.playAutomatic(camera) } },
                    nextCameraName = next?.displayName,
                    onNext = next?.let { camera -> { viewModel.playAutomatic(camera) } },
                    onOpenEarlier = playbackRequest.cameraName
                        ?.takeIf { cameraName ->
                            playbackRequest.kind == PlaybackKind.LIVE &&
                                cameras.any { it.name == cameraName }
                        }
                        ?.let { cameraName ->
                            {
                                activityPageName = ActivityPage.HISTORY.name
                                activityBackDestinationName = AppDestination.CAMERAS.name
                                activityBackFocusKey = "cameras:auto:$cameraName"
                                restoreFocusKey = "activity:page:${ActivityPage.HISTORY.name}"
                                playbackReturnFocusKey = null
                                viewModel.loadHistory(cameraName, null)
                                destinationName = AppDestination.ACTIVITY.name
                                viewModel.closePlayback()
                            }
                        },
                    activityReviewed = activityItem?.hasBeenReviewed,
                    markingActivityReviewed = activityItem != null &&
                        state.review.markingReviewedItemId == activityItem.id,
                    onMarkActivityReviewed = activityItem?.let { item ->
                        { viewModel.setReviewReviewed(item, !item.hasBeenReviewed) }
                    },
                    stretchVideo = playbackRequest.cameraName
                        ?.let { it in state.settings.stretchedCameraNames }
                        ?: false,
                    onToggleStretchVideo = playbackRequest.cameraName?.let { cameraName ->
                        {
                            viewModel.updateCameraStretch(
                                cameraName,
                                cameraName !in state.settings.stretchedCameraNames,
                            )
                        }
                    },
                    savingActivityRecording = when {
                        activityItem != null -> state.review.savingClipItemId == activityItem.id
                        historySaveKey != null -> state.history.savingSlotStartTime ==
                            playbackRequest.recordingStartTime
                        else -> false
                    },
                    activityRecordingSaved = when {
                        activityItem != null -> activityItem.id in state.review.savedClipItemIds
                        historySaveKey != null -> historySaveKey in state.history.savedSlotKeys
                        else -> false
                    },
                    activityRecordingMessage = when {
                        activityItem != null -> state.review.savedClipMessage
                            .takeIf { state.review.savedClipItemId == activityItem.id }
                        historySaveKey != null -> state.history.savedMessage
                        else -> null
                    },
                    onSaveActivityRecording = when {
                        activityItem != null -> { { viewModel.saveReviewClip(activityItem) } }
                        historySaveKey != null -> { { viewModel.saveHistoryClip(playbackRequest) } }
                        else -> null
                    },
                )
            }

            RootSurface.CAMERA_GROUP -> {
                val cameraGroupView = requireNotNull(state.cameraGroupView)
                CameraGroupViewScreen(
                    state = cameraGroupView,
                    preferRtpTcp = state.settings.preferRtpTcp,
                    pictureInPictureAvailable = pictureInPictureAvailable,
                    pictureInPictureActive = pictureInPictureActive,
                    onEnterPictureInPicture = onEnterPictureInPicture,
                    onBack = {
                        destinationName = AppDestination.CAMERAS.name
                        restoreFocusKey = cameraGroupOriginFocusKey
                        cameraGroupOriginFocusKey = null
                        viewModel.closeCameraGroup()
                    },
                    cachedBitmap = { camera -> viewModel.cachedCameraImage(camera)?.bitmap },
                    refreshBitmap = { camera, height ->
                        viewModel.refreshCameraImage(camera, height).getOrNull()?.bitmap
                    },
                )
            }

            RootSurface.CONNECTED -> {
                BackHandler(
                    enabled = state.ptz.cameraName != null ||
                        destination != AppDestination.HOME ||
                        state.review.selectedItemId != null ||
                        state.exports.selectedItemId != null ||
                        settingsPage != SettingsPage.MAIN,
                ) {
                    if (state.ptz.cameraName != null) {
                        viewModel.closePtzControls()
                    } else if (destination == AppDestination.ACTIVITY && state.review.selectedItemId != null) {
                        viewModel.closeReviewItem()
                    } else if (destination == AppDestination.SAVED && state.exports.selectedItemId != null) {
                        viewModel.closeExport()
                    } else if (destination == AppDestination.ACTIVITY && activityPage != ActivityPage.RECENT) {
                        if (activityBackDestinationName == AppDestination.CAMERAS.name) {
                            destinationName = AppDestination.CAMERAS.name
                            restoreFocusKey = activityBackFocusKey
                            activityPageName = ActivityPage.RECENT.name
                        } else {
                            activityPageName = ActivityPage.RECENT.name
                            restoreFocusKey = null
                        }
                    } else if (destination == AppDestination.SETTINGS && settingsPage != SettingsPage.MAIN) {
                        settingsReturnPageName = settingsPage.name
                        settingsPageName = SettingsPage.MAIN.name
                    } else {
                        destinationName = AppDestination.HOME.name
                        settingsPageName = SettingsPage.MAIN.name
                        restoreFocusKey = lastHomeFocusKey
                    }
                }
                ConnectedShell(
                    destination = destination,
                    updateAvailable = state.appUpdate.updateAvailable,
                    unreviewedActivityCount = state.review.counts.unreviewedTotal,
                    onDestination = {
                        destinationName = it.name
                        if (it == AppDestination.ACTIVITY) {
                            activityPageName = ActivityPage.RECENT.name
                            activityBackDestinationName = AppDestination.HOME.name
                            activityBackFocusKey = lastHomeFocusKey
                        }
                        settingsPageName = SettingsPage.MAIN.name
                        settingsReturnPageName = null
                        restoreFocusKey = if (it == AppDestination.HOME) lastHomeFocusKey else null
                        playbackReturnFocusKey = null
                    },
                    contentClaimsInitialFocus = restoreFocusKey != null ||
                        (destination == AppDestination.SETTINGS && settingsPage != SettingsPage.MAIN) ||
                        (destination == AppDestination.ACTIVITY && state.review.selectedItemId != null) ||
                        (destination == AppDestination.SAVED && state.exports.selectedItemId != null),
                    contentEntryFocusRequester = when {
                        destination == AppDestination.HOME && state.snapshot?.cameras?.isNotEmpty() == true ->
                            homeCameraFocusRequester
                        destination == AppDestination.SETTINGS && settingsPage == SettingsPage.MAIN ->
                            settingsMainFocusRequester
                        else -> null
                    },
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        state.errorMessage?.let { error ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 34.dp, top = 18.dp, end = 34.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ScreenMessage(error, isError = true, modifier = Modifier.weight(1f))
                                Button(onClick = viewModel::clearError) { Text("Dismiss") }
                            }
                        }
                        when (destination) {
                            AppDestination.HOME -> HomeScreen(
                                state = state,
                                restoreFocusKey = restoreFocusKey,
                                onFocusRestored = { restoreFocusKey = null },
                                onFocusKeyChanged = { lastHomeFocusKey = it },
                                initialCameraFocusRequester = homeCameraFocusRequester,
                                onOpenCameras = { destinationName = AppDestination.CAMERAS.name },
                                onOpenReview = {
                                    destinationName = AppDestination.ACTIVITY.name
                                    activityPageName = ActivityPage.RECENT.name
                                    activityBackDestinationName = AppDestination.HOME.name
                                    activityBackFocusKey = lastHomeFocusKey
                                },
                                onPlayCamera = { camera, key ->
                                    playbackReturnFocusKey = key
                                    returnDestinationName = AppDestination.HOME.name
                                    viewModel.playAutomatic(camera)
                                },
                                onPlayReview = { item, key ->
                                    playbackReturnFocusKey = key
                                    returnDestinationName = AppDestination.HOME.name
                                    viewModel.playReview(item)
                                },
                                cachedBitmap = { camera ->
                                    viewModel.cachedCameraImage(camera)?.bitmap
                                },
                                refreshBitmap = { camera, height ->
                                    viewModel.refreshCameraImage(camera, height).getOrNull()?.bitmap
                                },
                                cachedReviewBitmap = { item -> viewModel.cachedReviewImage(item)?.bitmap },
                                refreshReviewBitmap = { item, height ->
                                    viewModel.refreshReviewImage(item, height).getOrNull()?.bitmap
                                },
                            )

                            AppDestination.CAMERAS -> CamerasScreen(
                                state = state,
                                restoreFocusKey = restoreFocusKey,
                                onFocusRestored = { restoreFocusKey = null },
                                onPlayCamera = { camera, key ->
                                    playbackReturnFocusKey = key
                                    returnDestinationName = AppDestination.CAMERAS.name
                                    viewModel.playAutomatic(camera)
                                },
                                onOpenControls = viewModel::openPtzControls,
                                onRetryControls = viewModel::retryPtzControls,
                                onCloseControls = viewModel::closePtzControls,
                                onPtzCommand = viewModel::sendPtzCommand,
                                onOpenCameraGroup = { title, cameraNames, focusKey ->
                                    cameraGroupOriginFocusKey = focusKey
                                    viewModel.openCameraGroup(title, cameraNames)
                                },
                                onSaveCameraView = viewModel::saveCameraView,
                                onDeleteCameraView = viewModel::deleteCameraView,
                                cachedBitmap = { camera ->
                                    viewModel.cachedCameraImage(camera)?.bitmap
                                },
                                refreshBitmap = { camera, height ->
                                    viewModel.refreshCameraImage(camera, height).getOrNull()?.bitmap
                                },
                            )

                            AppDestination.BIRDSEYE -> BirdseyeScreen(
                                state = state,
                                restoreFocusKey = restoreFocusKey,
                                onFocusRestored = { restoreFocusKey = null },
                                onPlay = { key ->
                                    playbackReturnFocusKey = key
                                    returnDestinationName = AppDestination.BIRDSEYE.name
                                    viewModel.playBirdseye()
                                },
                                cachedBitmap = { camera ->
                                    viewModel.cachedCameraImage(camera)?.bitmap
                                },
                                refreshBitmap = { camera, height ->
                                    viewModel.refreshCameraImage(camera, height).getOrNull()?.bitmap
                                },
                            )

                            AppDestination.ACTIVITY -> when (activityPage) {
                                ActivityPage.RECENT -> ReviewScreen(
                                    state = state,
                                    restoreFocusKey = restoreFocusKey,
                                    onFocusRestored = { restoreFocusKey = null },
                                    onLoad = viewModel::loadReview,
                                    onLoadMore = viewModel::loadMoreReview,
                                    onMarkAllShownReviewed = viewModel::markAllShownReviewed,
                                    onPage = { page ->
                                        activityPageName = page.name
                                        restoreFocusKey = "activity:page:${page.name}"
                                    },
                                    onSeverity = viewModel::updateReviewSeverity,
                                    onApplyFilters = viewModel::applyReviewFilters,
                                    onResetFilters = viewModel::resetReviewFilters,
                                    onSelectItem = { item, key ->
                                        restoreFocusKey = key
                                        viewModel.selectReviewItem(item)
                                    },
                                    onCloseItem = viewModel::closeReviewItem,
                                    onPlayItem = { item ->
                                        returnDestinationName = AppDestination.ACTIVITY.name
                                        viewModel.playReview(item)
                                    },
                                    onSetReviewed = viewModel::setReviewReviewed,
                                    onSaveClip = viewModel::saveReviewClip,
                                    onFindSimilar = { item ->
                                        activityPageName = ActivityPage.SEARCH.name
                                        restoreFocusKey = "activity:page:${ActivityPage.SEARCH.name}"
                                        viewModel.findSimilarActivity(item)
                                    },
                                    cachedBitmap = { item -> viewModel.cachedReviewImage(item)?.bitmap },
                                    refreshBitmap = { item, height ->
                                        viewModel.refreshReviewImage(item, height).getOrNull()?.bitmap
                                    },
                                )

                                ActivityPage.HISTORY -> ActivityHistoryScreen(
                                    state = state,
                                    restoreFocusKey = restoreFocusKey,
                                    onFocusRestored = { restoreFocusKey = null },
                                    onPage = { page ->
                                        activityPageName = page.name
                                        restoreFocusKey = "activity:page:${page.name}"
                                    },
                                    onLoad = viewModel::loadHistory,
                                    onMoveHour = viewModel::moveHistoryHour,
                                    onPlay = { slot, key ->
                                        playbackReturnFocusKey = key
                                        returnDestinationName = AppDestination.ACTIVITY.name
                                        viewModel.playHistorySlot(slot)
                                    },
                                )

                                ActivityPage.SEARCH -> ActivitySearchScreen(
                                    state = state,
                                    restoreFocusKey = restoreFocusKey,
                                    onFocusRestored = { restoreFocusKey = null },
                                    onPage = { page ->
                                        activityPageName = page.name
                                        restoreFocusKey = "activity:page:${page.name}"
                                    },
                                    onSearch = viewModel::searchActivity,
                                    onLoadMore = viewModel::loadMoreActivitySearch,
                                    onPlay = { event, key ->
                                        playbackReturnFocusKey = key
                                        returnDestinationName = AppDestination.ACTIVITY.name
                                        viewModel.playSearchEvent(event)
                                    },
                                    cachedBitmap = { event -> viewModel.cachedSearchImage(event)?.bitmap },
                                    refreshBitmap = { event, height ->
                                        viewModel.refreshSearchImage(event, height).getOrNull()?.bitmap
                                    },
                                )
                            }

                            AppDestination.SAVED -> SavedRecordingsScreen(
                                state = state,
                                restoreFocusKey = restoreFocusKey,
                                onFocusRestored = { restoreFocusKey = null },
                                onLoad = viewModel::loadExports,
                                onSelect = { export, key ->
                                    restoreFocusKey = key
                                    viewModel.selectExport(export)
                                },
                                onClose = viewModel::closeExport,
                                onPlay = { export ->
                                    playbackReturnFocusKey = "saved:recording:${export.id}"
                                    returnDestinationName = AppDestination.SAVED.name
                                    viewModel.playExport(export)
                                },
                                onDelete = viewModel::deleteExport,
                                cachedBitmap = { export -> viewModel.cachedExportImage(export)?.bitmap },
                                refreshBitmap = { export, height ->
                                    viewModel.refreshExportImage(export, height).getOrNull()?.bitmap
                                },
                            )

                            AppDestination.SETTINGS -> when (settingsPage) {
                                SettingsPage.MAIN -> SettingsHubScreen(
                                    update = state.appUpdate,
                                    restorePage = settingsReturnPageName?.let { name ->
                                        runCatching { SettingsPage.valueOf(name) }.getOrNull()
                                    },
                                    initialFocusRequester = settingsMainFocusRequester,
                                    onFocusRestored = { settingsReturnPageName = null },
                                    onOpen = { page ->
                                        settingsReturnPageName = page.name
                                        settingsPageName = page.name
                                    },
                                )

                                SettingsPage.UPDATE -> UpdateSettingsScreen(
                                    update = state.appUpdate,
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.UPDATE.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    onCheckAgain = { viewModel.checkForUpdates(forceRefresh = true) },
                                    onDownload = viewModel::downloadUpdate,
                                    onInstall = onInstallUpdate,
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )

                                SettingsPage.APPEARANCE -> AppearanceSettingsScreen(
                                    state = state,
                                    onAppearance = viewModel::updateAppearance,
                                    onCustomTheme = viewModel::updateCustomTheme,
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.APPEARANCE.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )

                                SettingsPage.PLAYBACK -> PlaybackSettingsScreen(
                                    state = state,
                                    onStreamPreference = viewModel::updateStreamPreference,
                                    onCompatibilityMode = viewModel::updatePreferRtpTcp,
                                    onStartMuted = viewModel::updateStartLiveMuted,
                                    onPlaybackDetails = viewModel::updateDiagnosticsEnabled,
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.PLAYBACK.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )

                                SettingsPage.CONNECTION -> ConnectionSettingsScreen(
                                    state = state,
                                    onUpdateLiveRoute = viewModel::updateRtspRoute,
                                    onSignOut = { viewModel.logout(false) },
                                    onForgetServer = { viewModel.logout(true) },
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.CONNECTION.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )

                                SettingsPage.SYSTEM -> InformationScreen(
                                    state = state,
                                    onLoad = { viewModel.loadInformation() },
                                    onRefresh = { viewModel.loadInformation(force = true) },
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.SYSTEM.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                    initialTabName = initialInformationTabName,
                                )

                                SettingsPage.ADVANCED -> DiagnosticsScreen(
                                    state = state,
                                    onRefresh = viewModel::refresh,
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.ADVANCED.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )

                                SettingsPage.ABOUT -> AboutScreen(
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.ABOUT.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )
                            }
                        }
                    }
                }
            }

            RootSurface.LOADING -> StartupLoadingScreen(state.statusMessage)

            RootSurface.RECOVERY -> StartupRecoveryScreen(
                message = state.errorMessage,
                onRetry = viewModel::retrySavedSession,
                onConnectionSettings = viewModel::showConnectionSetup,
            )

            RootSurface.SETUP -> ConnectionSetupScreen(
                state = state,
                onDismissError = viewModel::clearError,
                onTestConnection = viewModel::testConnection,
                onConnect = viewModel::connect,
                onForget = { viewModel.logout(true) },
                onExitRequested = onExitRequested,
            )
        }
    }
}

@Composable
private fun ConnectedShell(
    destination: AppDestination,
    updateAvailable: Boolean,
    unreviewedActivityCount: Int,
    onDestination: (AppDestination) -> Unit,
    contentClaimsInitialFocus: Boolean,
    contentEntryFocusRequester: FocusRequester?,
    content: @Composable () -> Unit,
) {
    var contentFocusRequest by remember { mutableIntStateOf(0) }
    var shellRenderPass by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val navRequesters = remember { AppDestination.entries.associateWith { FocusRequester() } }
    val railClaimsInitialFocus = remember { !contentClaimsInitialFocus }
    val railExpansionState = remember { NavigationRailExpansionState() }
    LaunchedEffect(Unit) {
        if (!railClaimsInitialFocus) return@LaunchedEffect
        withFrameNanos { }
        navRequesters.getValue(destination).requestFocus()
    }
    LaunchedEffect(contentFocusRequest, destination) {
        if (contentFocusRequest == 0) return@LaunchedEffect
        withFrameNanos { }
        focusManager.moveFocus(FocusDirection.Right)
        contentFocusRequest = 0
    }
    LaunchedEffect(destination, contentClaimsInitialFocus) {
        withFrameNanos { }
        shellRenderPass += 1
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawWithContent {
                shellRenderPass
                drawContent()
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 60.dp)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionLeft) {
                        when (event.type) {
                            KeyEventType.KeyDown -> railExpansionState.armForNavigationFocus()
                            KeyEventType.KeyUp -> railExpansionState.cancelPendingExpansion()
                            else -> Unit
                        }
                    }
                    false
                }
                .onFocusChanged {
                    if (it.hasFocus) railExpansionState.contentFocused()
                },
        ) {
            content()
        }
        ConnectedNavigationRail(
            destination = destination,
            updateAvailable = updateAvailable,
            unreviewedActivityCount = unreviewedActivityCount,
            navRequesters = navRequesters,
            expansionState = railExpansionState,
            contentEntryFocusRequester = contentEntryFocusRequester,
            onSelect = { item ->
                railExpansionState.destinationSelected()
                onDestination(item)
                contentFocusRequest += 1
            },
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

@Stable
internal class NavigationRailExpansionState {
    var expanded by mutableStateOf(false)
        private set
    private var expandOnNextNavigationFocus = false

    fun armForNavigationFocus() {
        expandOnNextNavigationFocus = true
    }

    fun cancelPendingExpansion() {
        expandOnNextNavigationFocus = false
    }

    fun navigationFocused() {
        if (expandOnNextNavigationFocus) expanded = true
        expandOnNextNavigationFocus = false
    }

    fun navigationInput() {
        expanded = true
        expandOnNextNavigationFocus = false
    }

    fun contentFocused() {
        expanded = false
        expandOnNextNavigationFocus = false
    }

    fun destinationSelected() {
        expanded = false
        expandOnNextNavigationFocus = false
    }
}

@Composable
private fun ConnectedNavigationRail(
    destination: AppDestination,
    updateAvailable: Boolean,
    unreviewedActivityCount: Int,
    navRequesters: Map<AppDestination, FocusRequester>,
    expansionState: NavigationRailExpansionState,
    contentEntryFocusRequester: FocusRequester?,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = expansionState.expanded
    val visibleDestinations = AppDestination.entries
    Column(
        modifier = modifier
            .width(if (expanded) 148.dp else 60.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 12.dp)
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                ) {
                    expansionState.navigationInput()
                }
                false
            }
            .focusProperties {
                onEnter = { navRequesters.getValue(destination).requestFocus() }
            }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.opah_brand_mark),
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                )
                if (expanded) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Opah",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        visibleDestinations.forEachIndexed { index, item ->
            FocusCard(
                focusKey = "nav:${item.name.lowercase()}",
                restoreFocusKey = null,
                onFocusRestored = {},
                onClick = { onSelect(item) },
                selected = item == destination,
                accessibilityLabel = navigationAccessibilityLabel(
                    item,
                    updateAvailable,
                    unreviewedActivityCount,
                ),
                externalFocusRequester = navRequesters.getValue(item),
                onFocused = { expansionState.navigationFocused() },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties {
                        up = if (index == 0) {
                            FocusRequester.Cancel
                        } else {
                            navRequesters.getValue(visibleDestinations[index - 1])
                        }
                        down = if (index == visibleDestinations.lastIndex) {
                            FocusRequester.Cancel
                        } else {
                            navRequesters.getValue(visibleDestinations[index + 1])
                        }
                        left = FocusRequester.Cancel
                        right = if (item == destination && contentEntryFocusRequester != null) {
                            contentEntryFocusRequester
                        } else {
                            FocusRequester.Default
                        }
                    },
            ) {
                NavigationRailItemContent(
                    item,
                    destination,
                    updateAvailable,
                    unreviewedActivityCount,
                    expanded,
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun NavigationRailItemContent(
    item: AppDestination,
    destination: AppDestination,
    updateAvailable: Boolean,
    unreviewedActivityCount: Int,
    expanded: Boolean,
) {
    val iconTint = if (item == destination) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (expanded) 12.dp else 0.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.spacedBy(12.dp) else Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(item.iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(
                    if (item == AppDestination.ACTIVITY && unreviewedActivityCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        iconTint
                    },
                ),
                modifier = Modifier.size(28.dp),
            )
            if (item == AppDestination.SETTINGS && updateAvailable) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
            if (item == AppDestination.ACTIVITY) {
                compactActivityCount(unreviewedActivityCount)?.let { count ->
                    Text(
                        text = count,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = if (count.length > 2) 8.sp else 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (expanded) {
            Text(
                text = item.label,
                maxLines = 1,
                color = if (item == destination) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (item == destination) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

private fun List<Camera>.wrappedAt(index: Int): Camera? {
    if (isEmpty()) return null
    val wrapped = ((index % size) + size) % size
    return get(wrapped)
}
