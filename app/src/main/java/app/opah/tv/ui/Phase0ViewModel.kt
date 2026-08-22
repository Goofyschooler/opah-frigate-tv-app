package app.opah.tv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.opah.tv.OpahApplication
import app.opah.tv.BuildConfig
import app.opah.tv.data.ConnectionProfileFactory
import app.opah.tv.data.CameraImage
import app.opah.tv.data.DiscoveryBootstrap
import app.opah.tv.data.network.AuthenticationExpiredException
import app.opah.tv.data.network.InvalidCredentialsException
import app.opah.tv.data.network.OpahErrorCode
import app.opah.tv.data.network.PtzCommand
import app.opah.tv.data.network.PtzConnectionState
import app.opah.tv.data.network.PtzConnectionStatus
import app.opah.tv.data.network.toOpahFailure
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.CameraPtzInfo
import app.opah.tv.data.model.AppSettings
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.DeviceDiagnostics
import app.opah.tv.data.model.DiscoverySnapshot
import app.opah.tv.data.model.EventSearchQuery
import app.opah.tv.data.model.FrigateFeature
import app.opah.tv.data.model.LiveStreamOption
import app.opah.tv.data.model.MotionActivity
import app.opah.tv.data.model.RecordingHourSummary
import app.opah.tv.data.model.RecordingSegment
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewCounts
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.model.SearchEvent
import app.opah.tv.data.model.SavedCameraView
import app.opah.tv.data.model.RecordingExport
import app.opah.tv.data.model.FrigateInformationSummary
import app.opah.tv.data.model.StreamPreference
import app.opah.tv.data.model.ThemeColorPolicy
import app.opah.tv.data.update.AppUpdateAvailability
import app.opah.tv.data.update.AppUpdateCheckResult
import app.opah.tv.data.update.AppRelease
import app.opah.tv.domain.StreamUriFactory
import app.opah.tv.playback.PlaybackKind
import app.opah.tv.playback.PlaybackRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class InformationUiState(
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val summary: FrigateInformationSummary? = null,
    val errorMessage: String? = null,
)

data class ExportsUiState(
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val items: List<RecordingExport> = emptyList(),
    val errorMessage: String? = null,
    val deletingItemId: String? = null,
    val selectedItemId: String? = null,
)

data class AppUpdateUiState(
    val checking: Boolean = false,
    val checkedOnce: Boolean = false,
    val updateAvailable: Boolean = false,
    val latestVersion: String? = null,
    val releasePageUrl: String? = null,
    val releaseNotes: String = "",
    val availableRelease: AppRelease? = null,
    val downloading: Boolean = false,
    val preparedApkPath: String? = null,
    val errorMessage: String? = null,
)

data class PtzUiState(
    val cameraName: String? = null,
    val connection: PtzConnectionState = PtzConnectionState(),
    val errorMessage: String? = null,
)

data class CameraGroupStream(
    val camera: Camera,
    val uri: String,
    val videoMimeType: String? = null,
)

data class CameraGroupViewUiState(
    val title: String,
    val streams: List<CameraGroupStream>,
    val decoderWarning: Boolean = false,
)

private data class HistoryLoadResult(
    val hourStartSeconds: Double,
    val summaries: List<RecordingHourSummary>,
    val segments: List<RecordingSegment>,
    val motion: List<MotionActivity>,
)

internal fun decoderCapacityMayBeInsufficient(
    streams: List<CameraGroupStream>,
    device: DeviceDiagnostics?,
): Boolean = streams.mapNotNull(CameraGroupStream::videoMimeType)
    .groupingBy { it }
    .eachCount()
    .any { (mimeType, requiredInstances) ->
        val codec = device?.codecs?.firstOrNull { it.mimeType == mimeType } ?: return@any false
        val likelyHardwareDecoders = codec.decoders.filter { it.hardwareAccelerated != false }
            .ifEmpty { codec.decoders }
        val reportedMaximum = likelyHardwareDecoders.mapNotNull { it.maxSupportedInstances }.maxOrNull()
        reportedMaximum != null && requiredInstances > reportedMaximum
    }

internal fun AppUpdateUiState.afterSuccessfulCheck(result: AppUpdateCheckResult): AppUpdateUiState =
    AppUpdateUiState(
        checking = false,
        checkedOnce = true,
        updateAvailable = result.availability == AppUpdateAvailability.UPDATE_AVAILABLE,
        latestVersion = result.latestRelease.version.canonical,
        releasePageUrl = result.latestRelease.releasePageUrl,
        releaseNotes = result.latestRelease.releaseNotes,
        availableRelease = result.availableRelease,
        preparedApkPath = preparedApkPath.takeIf {
            result.availability == AppUpdateAvailability.UPDATE_AVAILABLE &&
                latestVersion == result.latestRelease.version.canonical
        },
    )

internal fun AppUpdateUiState.afterFailedCheck(message: String): AppUpdateUiState = copy(
    checking = false,
    checkedOnce = true,
    errorMessage = message,
)

data class Phase0UiState(
    val loading: Boolean = true,
    val connectionWorkInProgress: Boolean = false,
    val statusMessage: String = "Starting Opah…",
    val errorMessage: String? = null,
    val savedProfile: ConnectionProfile? = null,
    val activeProfile: ConnectionProfile? = null,
    val snapshot: DiscoverySnapshot? = null,
    val recentActivityLoaded: Boolean = false,
    val device: DeviceDiagnostics? = null,
    val playback: PlaybackRequest? = null,
    val cameraGroupView: CameraGroupViewUiState? = null,
    val activeCameraName: String? = null,
    val settings: AppSettings = AppSettings(),
    val review: ReviewBrowserState = ReviewBrowserState(),
    val history: HistoryBrowserState = HistoryBrowserState(),
    val activitySearch: ActivitySearchState = ActivitySearchState(),
    val information: InformationUiState = InformationUiState(),
    val exports: ExportsUiState = ExportsUiState(),
    val appUpdate: AppUpdateUiState = AppUpdateUiState(),
    val ptz: PtzUiState = PtzUiState(),
    val savedSessionRecoveryAvailable: Boolean = false,
)

class Phase0ViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OpahApplication).container
    private val repository = container.frigateRepository
    private val profileRepository = container.profileRepository
    private val sessionManager = container.sessionManager
    private val codecService = container.deviceMediaCapabilityService
    private val streamSelector = container.streamSelectionService
    private val logger = container.logger
    private val settingsRepository = container.settingsRepository
    private val appUpdateRepository = container.appUpdateRepository
    private val appUpdatePreparationRepository = container.appUpdatePreparationRepository
    private val cameraImageRepository = container.cameraImageRepository
    private val reviewImageRepository = container.reviewImageRepository
    private val ptzWebSocketClient = container.ptzWebSocketClient
    private val documentationImages = DocumentationImageStore(application)
    private var reviewLoadJob: Job? = null
    private var reviewDetailJob: Job? = null
    private var historyLoadJob: Job? = null
    private var activitySearchJob: Job? = null
    private var enrichmentJob: Job? = null
    private var updateCheckJob: Job? = null
    private var exportsLoadJob: Job? = null
    private var pendingCameraName: String? = null

    private val _state = MutableStateFlow(Phase0UiState())
    val state: StateFlow<Phase0UiState> = _state.asStateFlow()

    init {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.value = DocumentationFixtures.state(null)
        } else {
            viewModelScope.launch {
                ptzWebSocketClient.state.collect { connection ->
                    _state.update { current ->
                        current.copy(ptz = current.ptz.copy(connection = connection))
                    }
                }
            }
            viewModelScope.launch {
                settingsRepository.settings.collect { settings ->
                    _state.update { it.copy(settings = settings) }
                }
            }
            viewModelScope.launch(Dispatchers.Default) {
                runCatching { codecService.inspect() }
                    .onSuccess { device -> _state.update { it.copy(device = device) } }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        logger.warning("Device codec inspection failed", error)
                    }
            }
            viewModelScope.launch {
                val profile = profileRepository.load()
                _state.update { it.copy(savedProfile = profile) }

                val canRestore = profile != null && withContext(Dispatchers.IO) {
                    container.cookieJar.hasUnexpiredSession() || sessionManager.hasSavedCredential()
                }
                if (profile != null && canRestore) {
                    loadSavedSession(profile)
                } else if (profile != null) {
                    _state.update {
                        it.copy(loading = false, statusMessage = "Sign in to Frigate.")
                    }
                } else {
                    _state.update {
                        it.copy(
                            loading = false,
                            statusMessage = "Enter your Frigate connection.",
                        )
                    }
                }
            }
        }
    }

    fun setDocumentationScenario(scenario: String?) {
        if (!BuildConfig.DOCUMENTATION_MODE) return
        _state.value = DocumentationFixtures.state(scenario)
    }

    fun checkForUpdates(forceRefresh: Boolean = false) {
        if (BuildConfig.DOCUMENTATION_MODE || updateCheckJob?.isActive == true) return
        _state.update {
            it.copy(appUpdate = it.appUpdate.copy(checking = true, errorMessage = null))
        }
        updateCheckJob = viewModelScope.launch {
            try {
                val result = appUpdateRepository.check(forceRefresh)
                _state.update { current ->
                    current.copy(appUpdate = current.appUpdate.afterSuccessfulCheck(result))
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                logger.warning("App update check failed", error)
                _state.update { current ->
                    current.copy(
                        appUpdate = current.appUpdate.afterFailedCheck(
                            "Opah couldn't check for an update. Try again later.",
                        ),
                    )
                }
            }
        }
    }

    fun downloadUpdate() {
        val release = _state.value.appUpdate.availableRelease ?: return
        if (_state.value.appUpdate.downloading) return
        _state.update {
            it.copy(
                appUpdate = it.appUpdate.copy(
                    downloading = true,
                    preparedApkPath = null,
                    errorMessage = null,
                ),
            )
        }
        viewModelScope.launch {
            runCatching { appUpdatePreparationRepository.prepare(release) }
                .onSuccess { prepared ->
                    _state.update {
                        it.copy(
                            appUpdate = it.appUpdate.copy(
                                downloading = false,
                                preparedApkPath = prepared.apkPath,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    logger.warning("App update download or verification failed", error)
                    _state.update {
                        it.copy(
                            appUpdate = it.appUpdate.copy(
                                downloading = false,
                                preparedApkPath = null,
                                errorMessage = (error as? app.opah.tv.data.update.AppUpdateException)?.message
                                    ?: "Opah couldn't verify the update",
                            ),
                        )
                    }
                }
        }
    }

    fun reportUpdateInstallError() {
        _state.update {
            it.copy(
                appUpdate = it.appUpdate.copy(
                    errorMessage = "Android couldn't open the update installer",
                ),
            )
        }
    }

    fun connect(
        rawUrl: String,
        username: String,
        password: String,
        rtspHostOverride: String,
        rtspPortText: String,
    ) {
        if (_state.value.loading) return
        val profileResult = ConnectionProfileFactory.create(
            rawApiBaseUrl = rawUrl,
            username = username,
            rtspHostOverride = rtspHostOverride,
            rtspPort = rtspPortText.toIntOrNull() ?: -1,
        )
        val profile = profileResult.getOrElse { error ->
            _state.update { it.copy(errorMessage = error.message ?: "Invalid connection settings.") }
            return
        }

        viewModelScope.launch {
            enrichmentJob?.cancel()
            beginConnectionWork("Connecting…")
            try {
                val user = sessionManager.signIn(profile, password)
                val bootstrap = repository.discoverEssential(profile, user)
                clearImageCaches()
                publishConnected(profile, bootstrap)
                startEnrichment(profile, bootstrap)
            } catch (error: Throwable) {
                showFailure(error)
            }
        }
    }

    fun testConnection(
        rawUrl: String,
        username: String,
        password: String,
        rtspHostOverride: String,
        rtspPortText: String,
    ) {
        if (_state.value.loading) return
        val profile = createProfile(
            rawUrl = rawUrl,
            username = username,
            rtspHostOverride = rtspHostOverride,
            rtspPortText = rtspPortText,
        ) ?: return

        viewModelScope.launch {
            beginConnectionWork("Testing connection…")
            runCatching {
                sessionManager.testConnection(profile, password) { user ->
                    repository.discover(profile, user)
                }
            }
                .onSuccess { snapshot ->
                    _state.update {
                        it.copy(
                            loading = false,
                            connectionWorkInProgress = false,
                            statusMessage = "Connected • ${snapshot.cameras.size} cameras",
                            errorMessage = null,
                        )
                    }
                }
                .onFailure(::showFailure)
        }
    }

    fun refresh() {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update { current ->
                DocumentationFixtures.state(null).copy(settings = current.settings)
            }
            return
        }
        val profile = _state.value.activeProfile ?: return
        enrichmentJob?.cancel()
        ptzWebSocketClient.disconnect()
        viewModelScope.launch {
            beginWork("Refreshing Frigate diagnostics…")
            runCatching {
                val user = sessionManager.restore(profile)
                repository.discover(profile, user)
            }
                .onSuccess { snapshot ->
                    clearImageCaches()
                    _state.update {
                        it.copy(
                            loading = false,
                            statusMessage = "Connected",
                            errorMessage = null,
                            snapshot = snapshot,
                            recentActivityLoaded = true,
                            cameraGroupView = null,
                            review = ReviewBrowserState(),
                            history = HistoryBrowserState(),
                            activitySearch = ActivitySearchState(),
                            information = InformationUiState(),
                            exports = ExportsUiState(),
                            ptz = PtzUiState(),
                        )
                    }
                }
                .onFailure(::handleConnectedFailure)
        }
    }

    fun logout(forgetServer: Boolean = false) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            setDocumentationScenario("SETUP")
            return
        }
        val profile = _state.value.activeProfile ?: _state.value.savedProfile
        reviewLoadJob?.cancel()
        reviewDetailJob?.cancel()
        historyLoadJob?.cancel()
        activitySearchJob?.cancel()
        exportsLoadJob?.cancel()
        enrichmentJob?.cancel()
        ptzWebSocketClient.disconnect()
        viewModelScope.launch {
            sessionManager.signOut(profile, forgetServer)
            _state.update {
                it.copy(
                    loading = false,
                    statusMessage = if (forgetServer) "Enter your Frigate connection." else "Signed out",
                    errorMessage = null,
                    savedProfile = if (forgetServer) null else profile,
                    activeProfile = null,
                    snapshot = null,
                    recentActivityLoaded = false,
                    playback = null,
                    cameraGroupView = null,
                    activeCameraName = null,
                    review = ReviewBrowserState(),
                    history = HistoryBrowserState(),
                    activitySearch = ActivitySearchState(),
                    information = InformationUiState(),
                    exports = ExportsUiState(),
                    ptz = PtzUiState(),
                    savedSessionRecoveryAvailable = false,
                )
            }
            clearImageCaches()
        }
    }

    fun playAutomatic(camera: Camera) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationFixtures.livePlayback(camera),
                    activeCameraName = camera.name,
                    errorMessage = null,
                )
            }
            return
        }
        val current = _state.value
        val profile = current.activeProfile ?: return
        val codecs = current.device?.codecs.orEmpty()
        streamSelector.select(camera, codecs, current.settings.streamPreference)
            .onSuccess { playStream(profile, camera, it.option, it.reason) }
            .onFailure { error ->
                _state.update { it.copy(errorMessage = error.message ?: "No compatible stream.") }
            }
    }

    fun openPtzControls(camera: Camera) {
        val current = _state.value
        val info = current.snapshot?.ptzCameras?.get(camera.name) ?: return
        if (!info.hasControls) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    ptz = PtzUiState(
                        cameraName = camera.name,
                        connection = PtzConnectionState(PtzConnectionStatus.CONNECTED),
                    ),
                )
            }
            return
        }
        val profile = current.activeProfile ?: return
        _state.update { it.copy(ptz = PtzUiState(cameraName = camera.name)) }
        ptzWebSocketClient.connect(profile)
    }

    fun retryPtzControls() {
        val current = _state.value
        val cameraName = current.ptz.cameraName ?: return
        if (current.snapshot?.ptzCameras?.get(cameraName)?.hasControls != true) return
        val profile = current.activeProfile ?: return
        _state.update { it.copy(ptz = it.ptz.copy(errorMessage = null)) }
        ptzWebSocketClient.connect(profile)
    }

    fun sendPtzCommand(command: PtzCommand) {
        val current = _state.value
        val cameraName = current.ptz.cameraName ?: return
        val info = current.snapshot?.ptzCameras?.get(cameraName) ?: return
        if (!info.supports(command)) return
        if (BuildConfig.DOCUMENTATION_MODE) return
        if (!ptzWebSocketClient.send(cameraName, command)) {
            _state.update {
                it.copy(ptz = it.ptz.copy(errorMessage = "Camera controls are still connecting"))
            }
        } else if (current.ptz.errorMessage != null) {
            _state.update { it.copy(ptz = it.ptz.copy(errorMessage = null)) }
        }
    }

    fun closePtzControls() {
        val current = _state.value
        val cameraName = current.ptz.cameraName
        if (cameraName != null && current.ptz.connection.status == PtzConnectionStatus.CONNECTED) {
            ptzWebSocketClient.send(cameraName, PtzCommand.Stop)
        }
        ptzWebSocketClient.disconnect()
        _state.update { it.copy(ptz = PtzUiState()) }
    }

    fun openCameraByName(cameraName: String) {
        pendingCameraName = cameraName
        openPendingCamera()
    }

    fun playStream(camera: Camera, option: LiveStreamOption) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationFixtures.livePlayback(camera).copy(
                        detail = "${option.label} • Explicit stream selection",
                    ),
                    activeCameraName = camera.name,
                    errorMessage = null,
                )
            }
            return
        }
        val profile = _state.value.activeProfile ?: return
        playStream(profile, camera, option, "Explicit Frigate stream selection")
    }

    fun playBirdseye() {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationFixtures.birdseyePlayback(),
                    activeCameraName = null,
                    errorMessage = null,
                )
            }
            return
        }
        val current = _state.value
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        val birdseye = snapshot.birdseye
        if (!birdseye.playable) {
            _state.update {
                it.copy(errorMessage = "Frigate Birdseye is not ready for live playback.")
            }
            return
        }
        val streamName = requireNotNull(birdseye.streamName)
        runCatching { StreamUriFactory.rtsp(profile, streamName) }
            .onSuccess { uri ->
                _state.update {
                    it.copy(
                        playback = PlaybackRequest(
                            title = "Birdseye",
                            uri = uri,
                            kind = PlaybackKind.LIVE,
                            detail = "Frigate composite • Single RTSP stream",
                        ),
                        activeCameraName = null,
                        errorMessage = null,
                    )
                }
            }
            .onFailure { _state.update { state -> state.copy(errorMessage = it.message) } }
    }

    fun playReview(item: ReviewItem) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationFixtures.recordedPlayback(item),
                    activeCameraName = null,
                    errorMessage = null,
                )
            }
            return
        }
        val current = _state.value
        val profile = current.activeProfile ?: return
        val cameraName = current.snapshot?.cameras
            ?.firstOrNull { it.name == item.camera }
            ?.displayName
            ?: item.camera.replace('_', ' ').replaceFirstChar(Char::uppercase)
        val activityName = if (item.severity == ReviewSeverity.ALERT) "Alert" else "Activity"
        _state.update {
            it.copy(
                playback = PlaybackRequest(
                    title = "$activityName at $cameraName",
                    uri = repository.reviewPlaybackUrl(profile, item),
                    kind = PlaybackKind.RECORDED,
                    cameraName = item.camera,
                    detail = "Recording",
                    activityItemId = item.id,
                ),
                activeCameraName = null,
                errorMessage = null,
            )
        }
    }

    fun closePlayback() {
        _state.update { it.copy(playback = null, activeCameraName = null) }
    }

    fun openCameraGroup(title: String, cameraNames: List<String>) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        val selectedNames = cameraNames.distinct()
        if (selectedNames.size !in MIN_CAMERAS_PER_GROUP_VIEW..MAX_CAMERAS_PER_GROUP_VIEW) {
            _state.update { it.copy(errorMessage = "Choose two to four different cameras") }
            return
        }
        val cameras = selectedNames.mapNotNull { name -> snapshot.cameras.firstOrNull { it.name == name } }
        if (cameras.size != selectedNames.size) {
            _state.update { it.copy(errorMessage = "One of those cameras is not available") }
            return
        }
        val streams = runCatching {
            cameras.map { camera ->
                if (BuildConfig.DOCUMENTATION_MODE) {
                    CameraGroupStream(
                        camera = camera,
                        uri = "$DOCUMENTATION_URI_PREFIX${camera.name}",
                    )
                } else {
                    val profile = current.activeProfile ?: error("No active Frigate connection")
                    val selection = streamSelector.select(
                        camera,
                        current.device?.codecs.orEmpty(),
                        StreamPreference.LOW_BANDWIDTH,
                    ).getOrThrow()
                    CameraGroupStream(
                        camera = camera,
                        uri = StreamUriFactory.rtsp(profile, selection.option.streamName),
                        videoMimeType = selection.option.metadata?.videoCodec?.mimeType,
                    )
                }
            }
        }.getOrElse {
            _state.update { state -> state.copy(errorMessage = "Those cameras could not be opened together") }
            return
        }
        _state.update {
            it.copy(
                cameraGroupView = CameraGroupViewUiState(
                    title = title.trim().take(CAMERA_GROUP_VIEW_TITLE_LENGTH).ifEmpty { "Camera group" },
                    streams = streams,
                    decoderWarning = decoderCapacityMayBeInsufficient(streams, current.device),
                ),
                errorMessage = null,
            )
        }
    }

    fun closeCameraGroup() {
        _state.update { it.copy(cameraGroupView = null, playback = null, activeCameraName = null) }
    }

    fun saveCameraView(name: String, cameraNames: List<String>) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        val selected = cameraNames.distinct()
        if (
            selected.size !in MIN_CAMERAS_PER_GROUP_VIEW..MAX_CAMERAS_PER_GROUP_VIEW ||
            selected.any { name -> snapshot.cameras.none { it.name == name } }
        ) {
            _state.update { it.copy(errorMessage = "Choose two to four available cameras") }
            return
        }
        val cleanName = name.trim().replace(Regex("\\s+"), " ").take(SAVED_VIEW_NAME_LENGTH)
        if (cleanName.isEmpty()) {
            _state.update { it.copy(errorMessage = "Enter a name for this view") }
            return
        }
        updateSettings { settings ->
            val existing = settings.savedCameraViews.firstOrNull { it.cameraNames.toSet() == selected.toSet() }
            val view = SavedCameraView(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = cleanName,
                firstCameraName = selected[0],
                secondCameraName = selected[1],
                thirdCameraName = selected.getOrNull(2),
                fourthCameraName = selected.getOrNull(3),
            )
            settings.copy(savedCameraViews = settings.savedCameraViews.filterNot { it.id == view.id } + view)
        }
    }

    fun deleteCameraView(viewId: String) = updateSettings { settings ->
        settings.copy(savedCameraViews = settings.savedCameraViews.filterNot { it.id == viewId })
    }

    fun updateCameraStretch(cameraName: String, stretched: Boolean) = updateSettings { settings ->
        settings.copy(
            stretchedCameraNames = if (stretched) {
                settings.stretchedCameraNames + cameraName
            } else {
                settings.stretchedCameraNames - cameraName
            },
        )
    }

    fun updateAppearance(mode: AppearanceMode) = updateSettings { it.copy(appearanceMode = mode) }

    fun updateCustomTheme(colors: CustomThemeColors) = updateSettings {
        it.copy(customThemeColors = ThemeColorPolicy.sanitize(colors))
    }

    fun loadInformation(force: Boolean = false) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    information = InformationUiState(
                        loadedOnce = true,
                        summary = DocumentationFixtures.information(),
                    ),
                )
            }
            return
        }
        val current = _state.value
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        if (current.information.loading || (!force && current.information.loadedOnce)) return
        viewModelScope.launch {
            _state.update {
                it.copy(information = it.information.copy(loading = true, errorMessage = null))
            }
            runCatching { repository.loadInformation(profile, snapshot) }
                .onSuccess { summary ->
                    _state.update {
                        it.copy(
                            information = InformationUiState(
                                loading = false,
                                loadedOnce = true,
                                summary = summary,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Frigate information load failed", error)
                        _state.update {
                            it.copy(
                                information = it.information.copy(
                                    loading = false,
                                    loadedOnce = true,
                                    errorMessage = error.message
                                        ?: error.toOpahFailure().userMessage,
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun updateStreamPreference(preference: StreamPreference) =
        updateSettings { it.copy(streamPreference = preference) }

    fun updatePreferRtpTcp(enabled: Boolean) = updateSettings { it.copy(preferRtpTcp = enabled) }

    fun updateStartLiveMuted(enabled: Boolean) =
        updateSettings { it.copy(startLiveMuted = enabled) }

    fun updateDiagnosticsEnabled(enabled: Boolean) =
        updateSettings { it.copy(diagnosticsEnabled = enabled) }

    fun updateRtspRoute(hostOverride: String, portText: String) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            val profile = _state.value.activeProfile ?: _state.value.savedProfile ?: return
            val updated = profile.copy(
                rtspHostOverride = hostOverride.trim().ifBlank { null },
                rtspPort = portText.toIntOrNull() ?: profile.rtspPort,
            )
            _state.update {
                it.copy(
                    savedProfile = updated,
                    activeProfile = if (it.activeProfile == null) null else updated,
                    statusMessage = "RTSP route updated",
                    errorMessage = null,
                )
            }
            return
        }
        val current = _state.value
        val profile = current.activeProfile ?: current.savedProfile ?: return
        val updated = createProfile(
            rawUrl = profile.apiBaseUrl,
            username = profile.username,
            rtspHostOverride = hostOverride,
            rtspPortText = portText,
        ) ?: return
        viewModelScope.launch {
            profileRepository.save(updated)
            _state.update {
                it.copy(
                    savedProfile = updated,
                    activeProfile = if (it.activeProfile == null) null else updated,
                    statusMessage = "RTSP route updated",
                    errorMessage = null,
                )
            }
        }
    }

    fun cachedCameraImage(cameraName: String): CameraImage? = if (BuildConfig.DOCUMENTATION_MODE) {
        documentationImages.camera(cameraName)
    } else {
        _state.value.activeProfile?.let { cameraImageRepository.cached(it, cameraName) }
    }

    suspend fun refreshCameraImage(cameraName: String, height: Int = 360): Result<CameraImage> {
        if (BuildConfig.DOCUMENTATION_MODE) {
            return documentationImages.camera(cameraName)?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("Documentation image is unavailable."))
        }
        val profile = _state.value.activeProfile
            ?: return Result.failure(IllegalStateException("No active Frigate connection."))
        return cameraImageRepository.refresh(profile, cameraName, height)
    }

    fun loadReview() {
        if (BuildConfig.DOCUMENTATION_MODE) {
            val filters = _state.value.review.filters
            val items = DocumentationFixtures.reviewItems().filter { item ->
                (filters.severity == null || item.severity == filters.severity) &&
                    (filters.camera == null || item.camera == filters.camera) &&
                    (filters.label == null || filters.label in item.objects) &&
                    (filters.zone == null || filters.zone in item.zones) &&
                    (filters.reviewStatus.apiValue == null ||
                        item.hasBeenReviewed == filters.reviewStatus.apiValue)
            }
            _state.update {
                val allItems = DocumentationFixtures.reviewItems()
                val alertItems = allItems.filter { item -> item.severity == ReviewSeverity.ALERT }
                val detectionItems = allItems.filter { item -> item.severity == ReviewSeverity.DETECTION }
                it.copy(
                    review = it.review.copy(
                        items = items,
                        knownLabels = DocumentationFixtures.reviewItems().flatMap(ReviewItem::objects).toSet(),
                        knownZones = DocumentationFixtures.reviewItems().flatMap(ReviewItem::zones).toSet(),
                        counts = app.opah.tv.data.model.ReviewCounts(
                            reviewedAlerts = alertItems.count(ReviewItem::hasBeenReviewed),
                            reviewedDetections = detectionItems.count(ReviewItem::hasBeenReviewed),
                            totalAlerts = alertItems.size,
                            totalDetections = detectionItems.size,
                        ),
                        loading = false,
                        loadedOnce = true,
                        hasMore = false,
                        nextBeforeBySeverity = emptyMap(),
                        errorMessage = null,
                        selectedItemId = null,
                        recordingState = ReviewRecordingState.IDLE,
                        detailErrorMessage = null,
                    ),
                )
            }
            return
        }
        val current = _state.value
        val profile = current.activeProfile ?: return
        val allowedCameras = current.snapshot?.user?.allowedCameras.orEmpty()
        if (allowedCameras.isEmpty()) return
        val filters = current.review.filters
        reviewLoadJob?.cancel()
        reviewDetailJob?.cancel()
        reviewLoadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    review = it.review.copy(
                        loading = true,
                        countsLoading = true,
                        errorMessage = null,
                        selectedItemId = null,
                        recordingState = ReviewRecordingState.IDLE,
                        detailErrorMessage = null,
                    ),
                )
            }
            runCatching {
                coroutineScope {
                    val discovery = async {
                        repository.searchReview(
                            profile = profile,
                            allowedCameras = allowedCameras,
                            query = filters.toSearchQuery(
                                allowedCameras = allowedCameras,
                                nowSeconds = System.currentTimeMillis() / 1000.0,
                            ),
                        )
                    }
                    val counts = async { runCatching { repository.loadReviewCounts(profile, allowedCameras) } }
                    discovery.await() to counts.await()
                }
            }.onSuccess { (discovery, countsResult) ->
                _state.update { state ->
                    val labels = discovery.items.flatMap(ReviewItem::objects)
                        .filter(String::isNotBlank)
                        .toSet()
                    val zones = discovery.items.flatMap(ReviewItem::zones)
                        .filter(String::isNotBlank)
                        .toSet()
                    state.copy(
                        review = state.review.copy(
                            items = discovery.items,
                            knownLabels = state.review.knownLabels + labels,
                            knownZones = state.review.knownZones + zones,
                            counts = countsResult.getOrDefault(state.review.counts),
                            countsLoading = false,
                            loading = false,
                            loadedOnce = true,
                            hasMore = discovery.nextBeforeBySeverity.isNotEmpty(),
                            nextBeforeBySeverity = discovery.nextBeforeBySeverity,
                            errorMessage = discovery.warnings.firstOrNull(),
                        ),
                    )
                }
            }.onFailure { error ->
                if (error is AuthenticationExpiredException) {
                    handleConnectedFailure(error)
                } else {
                    logger.warning("Frigate Review load failed", error)
                    _state.update {
                        it.copy(
                            review = it.review.copy(
                                loading = false,
                                countsLoading = false,
                                loadedOnce = true,
                                errorMessage = error.toOpahFailure().userMessage,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun updateReviewSeverity(severity: ReviewSeverity?) =
        updateReviewFilters { it.copy(severity = severity) }

    fun loadMoreReview() {
        val current = _state.value
        if (
            BuildConfig.DOCUMENTATION_MODE || current.review.loading || current.review.loadingMore ||
            !current.review.hasMore
        ) return
        val profile = current.activeProfile ?: return
        val allowedCameras = current.snapshot?.user?.allowedCameras.orEmpty()
        val filters = current.review.filters
        viewModelScope.launch {
            _state.update { it.copy(review = it.review.copy(loadingMore = true, errorMessage = null)) }
            runCatching {
                repository.searchReview(
                    profile = profile,
                    allowedCameras = allowedCameras,
                    query = filters.toSearchQuery(
                        allowedCameras = allowedCameras,
                        nowSeconds = System.currentTimeMillis() / 1000.0,
                    ),
                    beforeBySeverity = current.review.nextBeforeBySeverity,
                )
            }.onSuccess { discovery ->
                _state.update { state ->
                    val combined = (state.review.items + discovery.items).distinctBy(ReviewItem::id)
                    state.copy(
                        review = state.review.copy(
                            items = combined,
                            knownLabels = state.review.knownLabels + discovery.items.flatMap(ReviewItem::objects),
                            knownZones = state.review.knownZones + discovery.items.flatMap(ReviewItem::zones),
                            loadingMore = false,
                            hasMore = discovery.nextBeforeBySeverity.isNotEmpty(),
                            nextBeforeBySeverity = discovery.nextBeforeBySeverity,
                        ),
                    )
                }
            }.onFailure { error ->
                if (error is AuthenticationExpiredException) {
                    handleConnectedFailure(error)
                } else {
                    logger.warning("Loading more Frigate Review items failed", error)
                    _state.update {
                        it.copy(
                            review = it.review.copy(
                                loadingMore = false,
                                errorMessage = error.toOpahFailure().userMessage,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun applyReviewFilters(filters: ReviewFilters) = updateReviewFilters { filters }

    fun resetReviewFilters() {
        _state.update {
            it.copy(review = it.review.copy(filters = it.review.filters.clearDetails()))
        }
        loadReview()
    }

    fun selectReviewItem(item: ReviewItem) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    review = it.review.copy(
                        selectedItemId = item.id,
                        recordingState = ReviewRecordingState.AVAILABLE,
                        detailErrorMessage = null,
                        detailLoading = false,
                    ),
                )
            }
            return
        }
        val profile = _state.value.activeProfile ?: return
        val allowedCameras = _state.value.snapshot?.user?.allowedCameras.orEmpty()
        reviewDetailJob?.cancel()
        _state.update {
            it.copy(
                review = it.review.copy(
                    selectedItemId = item.id,
                    recordingState = ReviewRecordingState.CHECKING,
                    detailErrorMessage = null,
                    detailLoading = true,
                ),
            )
        }
        reviewDetailJob = viewModelScope.launch {
            val (recordingResult, enrichmentResult) = coroutineScope {
                val recording = async { repository.reviewRecordingAvailable(profile, item) }
                val enrichment = async {
                    runCatching { repository.enrichReviewItem(profile, allowedCameras, item) }
                }
                recording.await() to enrichment.await()
            }
            val authError = listOf(recordingResult.exceptionOrNull(), enrichmentResult.exceptionOrNull())
                .filterIsInstance<AuthenticationExpiredException>()
                .firstOrNull()
            if (authError != null) {
                handleConnectedFailure(authError)
                return@launch
            }
            recordingResult.exceptionOrNull()?.let {
                logger.warning("Review recording availability check failed", it)
            }
            enrichmentResult.exceptionOrNull()?.let {
                logger.warning("Review linked activity load failed", it)
            }
            _state.update { state ->
                if (state.review.selectedItemId != item.id) return@update state
                val available = recordingResult.getOrNull()
                val enriched = enrichmentResult.getOrDefault(item)
                    .copy(recordingAvailable = available ?: item.recordingAvailable)
                state.copy(
                    review = state.review.copy(
                        items = state.review.items.map { candidate ->
                            if (candidate.id == item.id) enriched else candidate
                        },
                        recordingState = when (available) {
                            true -> ReviewRecordingState.AVAILABLE
                            false -> ReviewRecordingState.UNAVAILABLE
                            null -> ReviewRecordingState.UNKNOWN
                        },
                        detailLoading = false,
                        detailErrorMessage = if (available == null) {
                            "Recording availability could not be confirmed"
                        } else {
                            null
                        },
                    ),
                )
            }
        }
    }

    fun closeReviewItem() {
        reviewDetailJob?.cancel()
        _state.update {
            it.copy(
                review = it.review.copy(
                    selectedItemId = null,
                    recordingState = ReviewRecordingState.IDLE,
                    detailErrorMessage = null,
                    detailLoading = false,
                ),
            )
        }
    }

    fun setReviewReviewed(item: ReviewItem, reviewed: Boolean) {
        if (item.hasBeenReviewed == reviewed || _state.value.review.markingReviewedItemId != null) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            publishReviewStatus(item.id, reviewed)
            return
        }
        val profile = _state.value.activeProfile ?: return
        _state.update {
            it.copy(
                review = it.review.copy(
                    markingReviewedItemId = item.id,
                    detailErrorMessage = null,
                ),
            )
        }
        viewModelScope.launch {
            runCatching { repository.setReviewReviewed(profile, item, reviewed) }
                .onSuccess { publishReviewStatus(item.id, reviewed) }
                .onFailure { error ->
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Changing Frigate Review status failed", error)
                        _state.update { state ->
                            state.copy(
                                review = state.review.copy(
                                    markingReviewedItemId = null,
                                    detailErrorMessage = "Frigate couldn't update this activity",
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun markAllShownReviewed() {
        val current = _state.value
        val items = current.review.items.filterNot(ReviewItem::hasBeenReviewed)
        if (items.isEmpty() || current.review.markingAllReviewed) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            publishReviewStatuses(items.map(ReviewItem::id).toSet(), reviewed = true)
            return
        }
        val profile = current.activeProfile ?: return
        _state.update {
            it.copy(review = it.review.copy(markingAllReviewed = true, errorMessage = null))
        }
        viewModelScope.launch {
            runCatching { repository.setReviewsReviewed(profile, items, reviewed = true) }
                .onSuccess { publishReviewStatuses(items.map(ReviewItem::id).toSet(), reviewed = true) }
                .onFailure { error ->
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Marking displayed Frigate Review items failed", error)
                        _state.update {
                            it.copy(
                                review = it.review.copy(
                                    markingAllReviewed = false,
                                    errorMessage = "Frigate couldn't update the activity shown",
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun saveReviewClip(item: ReviewItem) {
        val current = _state.value
        if (!current.review.canSaveClip(item)) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    review = it.review.afterClipSaved(item.id),
                )
            }
            return
        }
        val profile = current.activeProfile ?: return
        val allowed = current.snapshot?.user?.allowedCameras.orEmpty()
        val cameraName = current.snapshot?.authorizedCameraNames?.get(item.camera)
            ?: item.camera.replace('_', ' ')
        val started = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date((item.startTime * 1_000).toLong()))
        val clipName = "$cameraName activity $started".take(256)
        _state.update {
            it.copy(
                review = it.review.copy(
                    savingClipItemId = item.id,
                    savedClipItemId = null,
                    savedClipMessage = null,
                    detailErrorMessage = null,
                ),
            )
        }
        viewModelScope.launch {
            runCatching { repository.saveReviewClip(profile, allowed, item, clipName) }
                .onSuccess {
                    _state.update { state ->
                        state.copy(
                            review = state.review.afterClipSaved(item.id),
                            exports = ExportsUiState(),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Saving Frigate recording clip failed", error)
                        _state.update { state ->
                            state.copy(
                                review = state.review.copy(
                                    savingClipItemId = null,
                                    detailErrorMessage = error.toOpahFailure().userMessage,
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun loadExports(force: Boolean = false) {
        val current = _state.value
        if (BuildConfig.DOCUMENTATION_MODE) return
        val profile = current.activeProfile ?: return
        val allowed = current.snapshot?.user?.allowedCameras.orEmpty()
        if (current.exports.loading || (!force && current.exports.loadedOnce)) return
        exportsLoadJob?.cancel()
        exportsLoadJob = viewModelScope.launch {
            _state.update { it.copy(exports = it.exports.copy(loading = true, errorMessage = null)) }
            runCatching { repository.loadExports(profile, allowed) }
                .onSuccess { items ->
                    _state.update {
                        it.copy(exports = ExportsUiState(loadedOnce = true, items = items))
                    }
                }
                .onFailure { error ->
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Loading Frigate saved clips failed", error)
                        _state.update {
                            it.copy(
                                exports = it.exports.copy(
                                    loading = false,
                                    loadedOnce = true,
                                    errorMessage = error.toOpahFailure().userMessage,
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun playExport(export: RecordingExport) {
        val current = _state.value
        val profile = current.activeProfile ?: return
        if (export.camera !in current.snapshot?.user?.allowedCameras.orEmpty()) return
        val url = if (BuildConfig.DOCUMENTATION_MODE) {
            "$DOCUMENTATION_URI_PREFIX${export.camera}?export=${export.id}"
        } else {
            repository.exportPlaybackUrl(profile, export)
        }
        if (url == null) {
            _state.update { it.copy(errorMessage = "This saved clip is not ready yet") }
            return
        }
        _state.update {
            it.copy(
                playback = PlaybackRequest(
                    title = export.name.replace('_', ' '),
                    uri = url,
                    kind = PlaybackKind.RECORDED,
                    cameraName = export.camera,
                    detail = "Saved clip",
                ),
                activeCameraName = null,
                errorMessage = null,
            )
        }
    }

    fun selectExport(export: RecordingExport) {
        _state.update {
            it.copy(
                exports = it.exports.copy(
                    selectedItemId = export.id,
                    errorMessage = null,
                ),
            )
        }
    }

    fun closeExport() {
        _state.update { it.copy(exports = it.exports.copy(selectedItemId = null, errorMessage = null)) }
    }

    fun deleteExport(export: RecordingExport) {
        val current = _state.value
        if (
            current.exports.deletingItemId != null ||
            current.snapshot?.user?.role?.equals("admin", ignoreCase = true) != true ||
            export.inProgress
        ) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    exports = it.exports.copy(
                        items = it.exports.items.filterNot { saved -> saved.id == export.id },
                        selectedItemId = null,
                    ),
                )
            }
            return
        }
        val profile = current.activeProfile ?: return
        val allowed = current.snapshot.user.allowedCameras
        val frigateVersion = current.snapshot.frigateVersion
        _state.update {
            it.copy(
                exports = it.exports.copy(
                    deletingItemId = export.id,
                    selectedItemId = null,
                    errorMessage = null,
                ),
            )
        }
        viewModelScope.launch {
            val deleteFailure = runCatching {
                repository.deleteExport(profile, allowed, export, frigateVersion)
            }.exceptionOrNull()
            if (deleteFailure is AuthenticationExpiredException) {
                handleConnectedFailure(deleteFailure)
                return@launch
            }
            if (deleteFailure != null && deleteFailure.toOpahFailure().code != OpahErrorCode.NOT_FOUND) {
                logger.warning("Deleting Frigate saved recording failed", deleteFailure)
                _state.update { state ->
                    state.copy(
                        exports = state.exports.copy(
                            deletingItemId = null,
                            errorMessage = deleteFailure.toOpahFailure().userMessage,
                        ),
                    )
                }
                return@launch
            }
            _state.update { state ->
                state.copy(
                    exports = state.exports.copy(
                        items = state.exports.items.filterNot { saved -> saved.id == export.id },
                        deletingItemId = null,
                        loading = true,
                        errorMessage = null,
                    ),
                )
            }
            runCatching { repository.loadExports(profile, allowed) }
                .onSuccess { refreshed ->
                    _state.update { state ->
                        state.copy(
                            exports = state.exports.copy(
                                items = refreshed,
                                loading = false,
                                loadedOnce = true,
                                errorMessage = null,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Refreshing Frigate saved recordings after deletion failed", error)
                        _state.update { state ->
                            state.copy(
                                exports = state.exports.copy(
                                    loading = false,
                                    errorMessage = "Saved recordings couldn't refresh",
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun cachedReviewImage(item: ReviewItem) = if (BuildConfig.DOCUMENTATION_MODE) {
        documentationImages.review(item)
    } else {
        _state.value.activeProfile?.let { reviewImageRepository.cached(it, item) }
    }

    suspend fun refreshReviewImage(item: ReviewItem, height: Int = 360) =
        if (BuildConfig.DOCUMENTATION_MODE) {
            documentationImages.review(item)?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("Documentation image is unavailable."))
        } else {
            _state.value.activeProfile?.let { reviewImageRepository.refresh(it, item, height) }
                ?: Result.failure(IllegalStateException("No active Frigate connection."))
        }

    fun cachedExportImage(export: RecordingExport) = if (BuildConfig.DOCUMENTATION_MODE) {
        documentationImages.export(export)
    } else {
        _state.value.activeProfile?.let { reviewImageRepository.cached(it, export) }
    }

    suspend fun refreshExportImage(export: RecordingExport, height: Int = 360) =
        if (BuildConfig.DOCUMENTATION_MODE) {
            documentationImages.export(export)?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("Documentation image is unavailable."))
        } else {
            _state.value.activeProfile?.let { reviewImageRepository.refresh(it, export, height) }
                ?: Result.failure(IllegalStateException("No active Frigate connection."))
        }

    fun loadHistory(
        cameraName: String? = null,
        requestedHourStartSeconds: Double? = null,
    ) {
        val current = _state.value
        val cameras = current.snapshot?.cameras.orEmpty()
        val selectedCamera = cameraName
            ?: current.history.cameraName?.takeIf { selected -> cameras.any { it.name == selected } }
            ?: cameras.firstOrNull()?.name
            ?: return
        val now = System.currentTimeMillis() / 1000.0
        val cameraChanged = selectedCamera != current.history.cameraName
        if (BuildConfig.DOCUMENTATION_MODE) {
            val summaries = documentationHistorySummaries(now)
            val selectedHour = (
                requestedHourStartSeconds
                    ?: current.history.hourStartSeconds?.takeUnless { cameraChanged }
                    ?: summaries.maxOfOrNull(::recordingHourStart)
                    ?: hourStart(now)
                ).coerceAtMost(hourStart(now))
            val end = (selectedHour + HISTORY_HOUR_SECONDS).coerceAtMost(now)
            _state.update {
                it.copy(
                    history = HistoryBrowserState(
                        cameraName = selectedCamera,
                        hourStartSeconds = selectedHour,
                        segments = DocumentationFixtures.recordingHistory(selectedHour, end),
                        hourSummaries = summaries,
                        motion = documentationMotionActivity(selectedCamera, selectedHour),
                        loadedOnce = true,
                        savedSlotKeys = it.history.savedSlotKeys,
                    ),
                )
            }
            return
        }
        val profile = current.activeProfile ?: return
        val allowedCameras = current.snapshot?.user?.allowedCameras.orEmpty()
        historyLoadJob?.cancel()
        historyLoadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    history = it.history.copy(
                        cameraName = selectedCamera,
                        hourStartSeconds = requestedHourStartSeconds ?: it.history.hourStartSeconds,
                        summaryLoading = cameraChanged || it.history.hourSummaries.isEmpty(),
                        loading = true,
                        savedMessage = null,
                        errorMessage = null,
                    ),
                )
            }
            runCatching {
                coroutineScope {
                    val summaries = if (cameraChanged || current.history.hourSummaries.isEmpty()) {
                        runCatching {
                            repository.loadRecordingHourSummaries(
                                profile,
                                allowedCameras,
                                selectedCamera,
                                TimeZone.getDefault().id,
                            )
                        }.getOrDefault(emptyList())
                    } else {
                        current.history.hourSummaries
                    }
                    val selectedHour = (
                        requestedHourStartSeconds
                            ?: current.history.hourStartSeconds?.takeUnless { cameraChanged }
                            ?: summaries.maxOfOrNull(::recordingHourStart)
                            ?: hourStart(now)
                        ).coerceAtMost(hourStart(now))
                    val end = (selectedHour + HISTORY_HOUR_SECONDS).coerceAtMost(now)
                    val segments = async {
                        repository.loadRecordingHistory(
                            profile,
                            allowedCameras,
                            selectedCamera,
                            selectedHour,
                            end,
                        )
                    }
                    val motion = async {
                        runCatching {
                            repository.loadMotionActivity(
                                profile,
                                allowedCameras,
                                selectedCamera,
                                selectedHour,
                                end,
                            )
                        }.getOrDefault(emptyList())
                    }
                    HistoryLoadResult(selectedHour, summaries, segments.await(), motion.await())
                }
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        history = it.history.copy(
                            hourStartSeconds = result.hourStartSeconds,
                            segments = result.segments,
                            hourSummaries = result.summaries,
                            motion = result.motion,
                            summaryLoading = false,
                            loading = false,
                            loadedOnce = true,
                        ),
                    )
                }
            }.onFailure { error ->
                if (error is AuthenticationExpiredException) {
                    handleConnectedFailure(error)
                } else {
                    logger.warning("Recording history load failed", error)
                    _state.update {
                        it.copy(
                            history = it.history.copy(
                                segments = emptyList(),
                                motion = emptyList(),
                                summaryLoading = false,
                                loading = false,
                                loadedOnce = true,
                                errorMessage = error.toOpahFailure().userMessage,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun moveHistoryHour(offsetHours: Int) {
        if (offsetHours == 0) return
        val current = _state.value.history
        val anchor = current.hourStartSeconds ?: hourStart(System.currentTimeMillis() / 1000.0)
        loadHistory(current.cameraName, anchor + offsetHours * HISTORY_HOUR_SECONDS)
    }

    fun playHistorySlot(slot: HistorySlot) {
        val playbackStart = slot.playbackStartTime ?: return
        val current = _state.value
        val cameraName = current.history.cameraName ?: return
        val camera = current.snapshot?.cameras?.firstOrNull { it.name == cameraName } ?: return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationFixtures.historyPlayback(camera, playbackStart, slot.endTime).copy(
                        recordingStartTime = playbackStart,
                        recordingEndTime = slot.endTime,
                        recordingSaveKey = historySlotKey(cameraName, slot),
                    ),
                    activeCameraName = null,
                )
            }
            return
        }
        val profile = current.activeProfile ?: return
        _state.update {
            it.copy(
                playback = PlaybackRequest(
                    title = camera.displayName,
                    uri = repository.recordingPlaybackUrl(profile, cameraName, playbackStart, slot.endTime),
                    kind = PlaybackKind.RECORDED,
                    cameraName = cameraName,
                    detail = "Earlier recording",
                    recordingStartTime = playbackStart,
                    recordingEndTime = slot.endTime,
                    recordingSaveKey = historySlotKey(cameraName, slot),
                ),
                activeCameraName = null,
                errorMessage = null,
            )
        }
    }

    fun saveHistoryClip(request: PlaybackRequest) {
        val current = _state.value
        val key = request.recordingSaveKey ?: return
        val camera = request.cameraName ?: return
        val start = request.recordingStartTime ?: return
        val end = request.recordingEndTime ?: return
        if (
            key in current.history.savedSlotKeys || current.history.savingSlotStartTime != null ||
            end <= start
        ) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    history = it.history.copy(
                        savedSlotKeys = it.history.savedSlotKeys + key,
                        savedMessage = "Recording saved in Frigate",
                    ),
                )
            }
            return
        }
        val profile = current.activeProfile ?: return
        val allowed = current.snapshot?.user?.allowedCameras.orEmpty()
        val displayName = current.snapshot?.authorizedCameraNames?.get(camera)
            ?: camera.replace('_', ' ')
        val started = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date((start * 1_000).toLong()))
        val name = "$displayName recording $started".take(256)
        _state.update {
            it.copy(history = it.history.copy(savingSlotStartTime = start, savedMessage = null))
        }
        viewModelScope.launch {
            runCatching { repository.saveRecordingClip(profile, allowed, camera, start, end, name) }
                .onSuccess {
                    _state.update { state ->
                        state.copy(
                            history = state.history.copy(
                                savingSlotStartTime = null,
                                savedSlotKeys = state.history.savedSlotKeys + key,
                                savedMessage = "Recording saved in Frigate",
                            ),
                            exports = ExportsUiState(),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Saving a History recording failed", error)
                        _state.update {
                            it.copy(
                                history = it.history.copy(
                                    savingSlotStartTime = null,
                                    savedMessage = error.toOpahFailure().userMessage,
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun searchActivity(text: String, filters: ActivitySearchFilters) {
        runActivitySearch(text.trim(), filters, eventId = null, similarLabel = null, append = false)
    }

    fun loadMoreActivitySearch() {
        val search = _state.value.activitySearch
        if (!search.hasMore || search.searching || search.loadingMore) return
        runActivitySearch(
            text = search.query,
            filters = search.filters,
            eventId = search.similarToEventId,
            similarLabel = search.similarToLabel,
            append = true,
        )
    }

    fun findSimilarActivity(item: ReviewItem) {
        val event = item.linkedEvents.firstOrNull() ?: return
        runActivitySearch(
            text = "",
            filters = ActivitySearchFilters(),
            eventId = event.id,
            similarLabel = event.subLabel?.takeIf(String::isNotBlank) ?: event.label,
            append = false,
        )
    }

    private fun runActivitySearch(
        text: String,
        filters: ActivitySearchFilters,
        eventId: String?,
        similarLabel: String?,
        append: Boolean,
    ) {
        val queryText = text.trim()
        if (queryText.isEmpty() && eventId.isNullOrBlank()) return
        val current = _state.value
        val snapshot = current.snapshot ?: return
        if (!snapshot.capabilities.supports(FrigateFeature.SEMANTIC_SEARCH)) return
        val allowedCameras = snapshot.user.allowedCameras
        val cameras = filters.cameraName?.let(::setOf) ?: allowedCameras
        val page = if (append) current.activitySearch.page + 1 else 1
        val now = System.currentTimeMillis() / 1000.0
        val after = filters.timeRange.seconds?.let { (now - it).coerceAtLeast(0.0) }
        val before = if (append) current.activitySearch.results.lastOrNull()?.startTime else null
        if (BuildConfig.DOCUMENTATION_MODE) {
            val results = DocumentationFixtures.searchEvents(
                queryText.ifBlank { similarLabel.orEmpty() },
                cameras,
            ).filter { event ->
                (filters.label == null || event.label == filters.label) &&
                    (filters.subLabel == null || event.subLabel == filters.subLabel) &&
                    (filters.zone == null || filters.zone in event.zones) &&
                    (filters.recognizedLicensePlate == null ||
                        event.recognizedLicensePlate == filters.recognizedLicensePlate)
            }
            _state.update {
                it.copy(
                    activitySearch = ActivitySearchState(
                        query = queryText,
                        filters = filters,
                        results = results,
                        searchedOnce = true,
                        similarToEventId = eventId,
                        similarToLabel = similarLabel,
                    ),
                )
            }
            return
        }
        val profile = current.activeProfile ?: return
        activitySearchJob?.cancel()
        activitySearchJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    activitySearch = it.activitySearch.copy(
                        query = queryText,
                        filters = filters,
                        searching = !append,
                        loadingMore = append,
                        similarToEventId = eventId,
                        similarToLabel = similarLabel,
                        errorMessage = null,
                    ),
                )
            }
            runCatching {
                repository.searchEvents(
                    profile = profile,
                    allowedCameras = allowedCameras,
                    query = EventSearchQuery(
                        text = queryText.takeIf(String::isNotBlank),
                        cameras = cameras,
                        after = after,
                        before = before,
                        label = filters.label,
                        subLabel = filters.subLabel,
                        zone = filters.zone,
                        recognizedLicensePlate = filters.recognizedLicensePlate,
                        eventId = eventId,
                        limit = ACTIVITY_SEARCH_PAGE_SIZE,
                    ),
                )
            }.onSuccess { results ->
                _state.update {
                    val combined = if (append) {
                        (it.activitySearch.results + results).distinctBy(SearchEvent::id)
                    } else {
                        results
                    }
                    it.copy(
                        activitySearch = it.activitySearch.copy(
                            results = combined,
                            searching = false,
                            loadingMore = false,
                            searchedOnce = true,
                            page = page,
                            hasMore = results.size >= ACTIVITY_SEARCH_PAGE_SIZE,
                        ),
                    )
                }
            }.onFailure { error ->
                if (error is AuthenticationExpiredException) {
                    handleConnectedFailure(error)
                } else {
                    logger.warning("Activity search failed", error)
                    _state.update {
                        it.copy(
                            activitySearch = it.activitySearch.copy(
                                results = if (append) it.activitySearch.results else emptyList(),
                                searching = false,
                                loadingMore = false,
                                searchedOnce = true,
                                errorMessage = error.toOpahFailure().userMessage,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun playSearchEvent(event: SearchEvent) {
        val current = _state.value
        val camera = current.snapshot?.cameras?.firstOrNull { it.name == event.camera } ?: return
        val start = (event.startTime - SEARCH_PLAYBACK_PADDING_SECONDS).coerceAtLeast(0.0)
        val end = (event.endTime ?: event.startTime + SEARCH_DEFAULT_DURATION_SECONDS) +
            SEARCH_PLAYBACK_PADDING_SECONDS
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationFixtures.searchPlayback(camera, event),
                    activeCameraName = null,
                )
            }
            return
        }
        val profile = current.activeProfile ?: return
        _state.update {
            it.copy(
                playback = PlaybackRequest(
                    title = "${event.label.replace('_', ' ').replaceFirstChar(Char::uppercase)} at ${camera.displayName}",
                    uri = repository.recordingPlaybackUrl(profile, event.camera, start, end),
                    kind = PlaybackKind.RECORDED,
                    cameraName = event.camera,
                    detail = "Search result",
                ),
                activeCameraName = null,
                errorMessage = null,
            )
        }
    }

    fun cachedSearchImage(event: SearchEvent) = if (BuildConfig.DOCUMENTATION_MODE) {
        DocumentationFixtures.reviewItemForEvent(event)?.let(documentationImages::review)
    } else {
        _state.value.activeProfile?.let { reviewImageRepository.cached(it, event) }
    }

    suspend fun refreshSearchImage(event: SearchEvent, height: Int = 360) =
        if (BuildConfig.DOCUMENTATION_MODE) {
            DocumentationFixtures.reviewItemForEvent(event)?.let(documentationImages::review)
                ?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("Documentation image is unavailable."))
        } else {
            _state.value.activeProfile?.let { reviewImageRepository.refresh(it, event, height) }
                ?: Result.failure(IllegalStateException("No active Frigate connection."))
        }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun retrySavedSession() {
        if (_state.value.loading) return
        val profile = _state.value.savedProfile ?: return
        viewModelScope.launch { loadSavedSession(profile, "Reconnecting…") }
    }

    fun showConnectionSetup() {
        if (_state.value.loading) return
        ptzWebSocketClient.disconnect()
        sessionManager.expireSession()
        _state.update {
            it.copy(
                statusMessage = "Sign in to Frigate.",
                errorMessage = null,
                activeProfile = null,
                snapshot = null,
                recentActivityLoaded = false,
                playback = null,
                cameraGroupView = null,
                activeCameraName = null,
                ptz = PtzUiState(),
                savedSessionRecoveryAvailable = false,
            )
        }
    }

    fun sessionExpired(message: String = "The Frigate session expired. Sign in again.") {
        sessionManager.expireSession()
        val profile = _state.value.activeProfile ?: _state.value.savedProfile
        if (profile == null) {
            finishSignedOut(message)
            return
        }
        viewModelScope.launch {
            if (!sessionManager.hasSavedCredential()) {
                finishSignedOut(message)
            } else {
                loadSavedSession(profile, "Reconnecting…")
            }
        }
    }

    private suspend fun loadSavedSession(
        profile: ConnectionProfile,
        progressMessage: String = "Connecting…",
    ) {
        beginWork(progressMessage)
        runCatching {
            val user = sessionManager.restore(profile)
            repository.discoverEssential(profile, user)
        }
            .onSuccess { bootstrap ->
                clearImageCaches()
                publishConnected(profile, bootstrap)
                startEnrichment(profile, bootstrap)
            }
            .onFailure { error ->
                if (error is AuthenticationExpiredException) {
                    finishSignedOut(error.message ?: "Sign in to Frigate.")
                } else if (error is InvalidCredentialsException) {
                    sessionManager.clearSavedCredential()
                    finishSignedOut("The saved sign-in is no longer valid. Enter the current password.")
                } else {
                    logger.warning("Saved Frigate session restore failed", error)
                    showSavedSessionRecovery(error)
                }
            }
    }

    private fun playStream(
        profile: ConnectionProfile,
        camera: Camera,
        option: LiveStreamOption,
        reason: String,
    ) {
        runCatching { StreamUriFactory.rtsp(profile, option.streamName) }
            .onSuccess { uri ->
                val fallbackOption = if (
                    option.label.contains("low", ignoreCase = true) ||
                    option.streamName.contains("sub", ignoreCase = true)
                ) {
                    null
                } else {
                    streamSelector.select(
                        camera,
                        _state.value.device?.codecs.orEmpty(),
                        StreamPreference.LOW_BANDWIDTH,
                    ).getOrNull()?.option?.takeIf { it.streamName != option.streamName }
                }
                val fallbackUri = fallbackOption?.let {
                    runCatching { StreamUriFactory.rtsp(profile, it.streamName) }.getOrNull()
                }
                _state.update {
                    it.copy(
                        playback = PlaybackRequest(
                            title = camera.displayName,
                            uri = uri,
                            kind = PlaybackKind.LIVE,
                            cameraName = camera.name,
                            detail = "${option.label} • $reason",
                            startupFallbackUri = fallbackUri,
                            startupFallbackDetail = fallbackOption?.let { fallback ->
                                "${fallback.label} • Requested stream produced no first frame within 8 seconds"
                            },
                        ),
                        activeCameraName = camera.name,
                        errorMessage = null,
                    )
                }
            }
            .onFailure { error ->
                _state.update { it.copy(errorMessage = error.message ?: "Invalid RTSP stream URL.") }
            }
    }

    private fun beginWork(message: String) {
        _state.update {
            it.copy(
                loading = true,
                statusMessage = message,
                errorMessage = null,
                savedSessionRecoveryAvailable = false,
            )
        }
    }

    private fun beginConnectionWork(message: String) {
        _state.update {
            it.copy(
                loading = true,
                connectionWorkInProgress = true,
                statusMessage = message,
                errorMessage = null,
                savedSessionRecoveryAvailable = false,
            )
        }
    }

    private fun publishConnected(profile: ConnectionProfile, bootstrap: DiscoveryBootstrap) {
        _state.update {
            it.copy(
                loading = false,
                connectionWorkInProgress = false,
                statusMessage = "Connected",
                errorMessage = null,
                savedProfile = profile,
                activeProfile = profile,
                snapshot = bootstrap.snapshot,
                recentActivityLoaded = false,
                cameraGroupView = null,
                review = ReviewBrowserState(),
                history = HistoryBrowserState(),
                activitySearch = ActivitySearchState(),
                information = InformationUiState(),
                ptz = PtzUiState(),
                savedSessionRecoveryAvailable = false,
            )
        }
        openPendingCamera()
    }

    private fun openPendingCamera() {
        val cameraName = pendingCameraName ?: return
        val current = _state.value
        if (current.activeProfile == null || current.snapshot == null) return
        pendingCameraName = null
        val camera = current.snapshot.cameras.firstOrNull { it.name == cameraName }
        if (camera == null) {
            _state.update {
                it.copy(errorMessage = "That camera is not available for this Frigate account.")
            }
            return
        }
        playAutomatic(camera)
    }

    private fun startEnrichment(profile: ConnectionProfile, bootstrap: DiscoveryBootstrap) {
        enrichmentJob?.cancel()
        enrichmentJob = viewModelScope.launch {
            runCatching {
                coroutineScope {
                    val snapshot = async { repository.enrich(profile, bootstrap) }
                    val counts = async {
                        runCatching {
                            repository.loadReviewCounts(profile, bootstrap.snapshot.user.allowedCameras)
                        }
                    }
                    snapshot.await() to counts.await()
                }
            }
                .onSuccess { (snapshot, counts) ->
                    _state.update { current ->
                        if (current.activeProfile == profile) {
                            current.copy(
                                snapshot = snapshot,
                                recentActivityLoaded = true,
                                review = current.review.copy(
                                    counts = counts.getOrDefault(current.review.counts),
                                ),
                            )
                        } else {
                            current
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    if (_state.value.activeProfile != profile) return@onFailure
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Frigate background enrichment failed", error)
                        _state.update { current ->
                            val snapshot = current.snapshot ?: return@update current
                            current.copy(
                                snapshot = snapshot.copy(
                                    warnings = snapshot.warnings +
                                        "Some Frigate details are still unavailable. Refresh to retry.",
                                ),
                            )
                        }
                    }
                }
        }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update { it.copy(settings = transform(it.settings)) }
        } else {
            viewModelScope.launch { settingsRepository.update(transform) }
        }
    }

    private fun createProfile(
        rawUrl: String,
        username: String,
        rtspHostOverride: String,
        rtspPortText: String,
    ): ConnectionProfile? {
        val profileResult = ConnectionProfileFactory.create(
            rawApiBaseUrl = rawUrl,
            username = username,
            rtspHostOverride = rtspHostOverride,
            rtspPort = rtspPortText.toIntOrNull() ?: -1,
        )
        return profileResult.getOrElse { error ->
            _state.update { it.copy(errorMessage = error.message ?: "Invalid connection settings.") }
            null
        }
    }

    private fun showFailure(error: Throwable) {
        logger.warning("Frigate operation failed", error)
        _state.update {
            it.copy(
                loading = false,
                connectionWorkInProgress = false,
                statusMessage = "Connection failed",
                errorMessage = error.toOpahFailure().userMessage,
            )
        }
    }

    private fun handleConnectedFailure(error: Throwable) {
        if (error is AuthenticationExpiredException) {
            sessionExpired(error.message ?: "The Frigate session expired. Sign in again.")
        } else {
            showFailure(error)
        }
    }

    private fun updateReviewFilters(transform: (ReviewFilters) -> ReviewFilters) {
        _state.update { it.copy(review = it.review.copy(filters = transform(it.review.filters))) }
        loadReview()
    }

    private fun publishReviewStatus(reviewId: String, reviewed: Boolean) {
        _state.update { state ->
            val removeFromCurrentResults = state.review.filters.reviewStatus.apiValue?.let {
                it != reviewed
            } == true
            val changedItem = state.review.items.firstOrNull { it.id == reviewId }
                ?: state.snapshot?.recentReviewItems?.firstOrNull { it.id == reviewId }
            val countedItem = changedItem?.takeIf(ReviewItem::isInReviewCountWindow)
            state.copy(
                snapshot = state.snapshot?.copy(
                    recentReviewItems = state.snapshot.recentReviewItems.withReviewStatus(reviewId, reviewed),
                ),
                review = state.review.copy(
                    items = state.review.items.afterReviewStatusChanged(
                        reviewId = reviewId,
                        reviewed = reviewed,
                        reviewStatus = state.review.filters.reviewStatus,
                    ),
                    selectedItemId = if (removeFromCurrentResults) null else state.review.selectedItemId,
                    recordingState = if (removeFromCurrentResults) {
                        ReviewRecordingState.IDLE
                    } else {
                        state.review.recordingState
                    },
                    markingReviewedItemId = null,
                    counts = state.review.counts.afterReviewStatusChanged(
                        wasReviewed = countedItem?.hasBeenReviewed,
                        reviewed = reviewed,
                        severity = countedItem?.severity,
                    ),
                    detailErrorMessage = null,
                ),
            )
        }
    }

    private fun publishReviewStatuses(reviewIds: Set<String>, reviewed: Boolean) {
        if (reviewIds.isEmpty()) return
        _state.update { state ->
            val changed = state.review.items.filter { it.id in reviewIds && it.hasBeenReviewed != reviewed }
            val removeFromCurrentResults = state.review.filters.reviewStatus.apiValue?.let {
                it != reviewed
            } == true
            val updatedItems = state.review.items.map { item ->
                if (item.id in reviewIds) item.copy(hasBeenReviewed = reviewed) else item
            }.let { items ->
                if (removeFromCurrentResults) items.filterNot { it.id in reviewIds } else items
            }
            val updatedCounts = changed.filter(ReviewItem::isInReviewCountWindow)
                .fold(state.review.counts) { counts, item ->
                counts.afterReviewStatusChanged(item.hasBeenReviewed, reviewed, item.severity)
            }
            state.copy(
                snapshot = state.snapshot?.copy(
                    recentReviewItems = state.snapshot.recentReviewItems.map { item ->
                        if (item.id in reviewIds) item.copy(hasBeenReviewed = reviewed) else item
                    },
                ),
                review = state.review.copy(
                    items = updatedItems,
                    counts = updatedCounts,
                    markingAllReviewed = false,
                    markingReviewedItemId = null,
                    selectedItemId = state.review.selectedItemId?.takeUnless {
                        removeFromCurrentResults && it in reviewIds
                    },
                    detailErrorMessage = null,
                ),
            )
        }
    }

    private fun finishSignedOut(message: String) {
        reviewLoadJob?.cancel()
        reviewDetailJob?.cancel()
        historyLoadJob?.cancel()
        activitySearchJob?.cancel()
        enrichmentJob?.cancel()
        ptzWebSocketClient.disconnect()
        sessionManager.expireSession()
        clearImageCaches()
        _state.update {
            it.copy(
                loading = false,
                statusMessage = "Sign in to Frigate.",
                errorMessage = message,
                activeProfile = null,
                snapshot = null,
                recentActivityLoaded = false,
                playback = null,
                cameraGroupView = null,
                activeCameraName = null,
                review = ReviewBrowserState(),
                history = HistoryBrowserState(),
                activitySearch = ActivitySearchState(),
                information = InformationUiState(),
                ptz = PtzUiState(),
                savedSessionRecoveryAvailable = false,
            )
        }
    }

    private fun showSavedSessionRecovery(error: Throwable) {
        reviewLoadJob?.cancel()
        reviewDetailJob?.cancel()
        historyLoadJob?.cancel()
        activitySearchJob?.cancel()
        enrichmentJob?.cancel()
        ptzWebSocketClient.disconnect()
        _state.update {
            it.copy(
                loading = false,
                statusMessage = "Frigate is unavailable",
                errorMessage = error.toOpahFailure().userMessage,
                activeProfile = null,
                snapshot = null,
                recentActivityLoaded = false,
                playback = null,
                cameraGroupView = null,
                activeCameraName = null,
                review = ReviewBrowserState(),
                history = HistoryBrowserState(),
                activitySearch = ActivitySearchState(),
                information = InformationUiState(),
                ptz = PtzUiState(),
                savedSessionRecoveryAvailable = it.savedProfile != null,
            )
        }
    }

    private fun clearImageCaches() {
        cameraImageRepository.clear()
        reviewImageRepository.clear()
    }

    override fun onCleared() {
        ptzWebSocketClient.disconnect()
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            Phase0ViewModel(application) as T
    }
}

private fun ReviewCounts.afterReviewStatusChanged(
    wasReviewed: Boolean?,
    reviewed: Boolean,
    severity: ReviewSeverity?,
): ReviewCounts {
    if (wasReviewed == null || wasReviewed == reviewed) return this
    val delta = if (reviewed) 1 else -1
    return when (severity) {
        ReviewSeverity.ALERT -> copy(reviewedAlerts = (reviewedAlerts + delta).coerceIn(0, totalAlerts))
        ReviewSeverity.DETECTION -> copy(
            reviewedDetections = (reviewedDetections + delta).coerceIn(0, totalDetections),
        )
        ReviewSeverity.UNKNOWN, null -> this
    }
}

private fun ReviewItem.isInReviewCountWindow(): Boolean =
    BuildConfig.DOCUMENTATION_MODE ||
        startTime >= (System.currentTimeMillis() / 1_000.0) - REVIEW_COUNT_WINDOW_SECONDS

private fun recordingHourStart(summary: RecordingHourSummary): Double {
    val parser = SimpleDateFormat("yyyy-MM-dd HH", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getDefault()
    }
    return parser.parse("${summary.day} ${summary.hour.toString().padStart(2, '0')}")
        ?.time
        ?.div(1_000.0)
        ?: 0.0
}

private fun documentationHistorySummaries(nowSeconds: Double): List<RecordingHourSummary> {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return (0..2).flatMap { dayOffset ->
        val daySeconds = nowSeconds - dayOffset * 24 * 60 * 60.0
        val day = format.format(Date((daySeconds * 1_000).toLong()))
        listOf(8, 12, 16, 20).map { hour ->
            RecordingHourSummary(day, hour, 3_300, 420.0 + hour, 180.0, 2 + hour % 3)
        }
    }.filter { recordingHourStart(it) <= hourStart(nowSeconds) }
}

private fun documentationMotionActivity(camera: String, hourStartSeconds: Double): List<MotionActivity> =
    listOf(4, 7, 18, 22, 36, 37, 48, 52).map { minute ->
        MotionActivity(hourStartSeconds + minute * 60, 6.0 + minute % 5, camera)
    }

private fun CameraPtzInfo.supports(command: PtzCommand): Boolean = when (command) {
    PtzCommand.MoveLeft,
    PtzCommand.MoveRight,
    PtzCommand.MoveUp,
    PtzCommand.MoveDown,
    -> canMove
    PtzCommand.ZoomIn, PtzCommand.ZoomOut -> canZoom
    PtzCommand.FocusIn, PtzCommand.FocusOut -> canFocus
    PtzCommand.Stop -> true
    is PtzCommand.Preset -> command.name.trim() in presets
}

private const val SEARCH_PLAYBACK_PADDING_SECONDS = 8.0
private const val SEARCH_DEFAULT_DURATION_SECONDS = 30.0
private const val ACTIVITY_SEARCH_PAGE_SIZE = 30
private const val REVIEW_COUNT_WINDOW_SECONDS = 24 * 60 * 60.0
private const val MIN_CAMERAS_PER_GROUP_VIEW = 2
private const val MAX_CAMERAS_PER_GROUP_VIEW = 4
private const val CAMERA_GROUP_VIEW_TITLE_LENGTH = 40
private const val SAVED_VIEW_NAME_LENGTH = 40
