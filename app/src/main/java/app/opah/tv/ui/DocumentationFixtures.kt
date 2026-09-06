package app.opah.tv.ui

import android.content.Context
import android.graphics.BitmapFactory
import app.opah.tv.briefing.BriefingAudience
import app.opah.tv.briefing.BriefingStoredCandidate
import app.opah.tv.briefing.BriefingSummarizer
import app.opah.tv.data.CameraImage
import app.opah.tv.data.ReviewImage
import app.opah.tv.data.model.AcceleratorPerformance
import app.opah.tv.data.model.AppSettings
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.AudioCodec
import app.opah.tv.data.model.BirdseyeStatus
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.CameraGroup
import app.opah.tv.data.model.CameraPtzInfo
import app.opah.tv.data.model.CameraPerformance
import app.opah.tv.data.model.CameraStorageUsage
import app.opah.tv.data.model.CodecCapability
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.DecoderCapability
import app.opah.tv.data.model.DetectorPerformance
import app.opah.tv.data.model.DeviceDiagnostics
import app.opah.tv.data.model.DiscoverySnapshot
import app.opah.tv.data.model.FrigateInformationSummary
import app.opah.tv.data.model.FrigatePerformanceSummary
import app.opah.tv.data.model.FrigateCapabilities
import app.opah.tv.data.model.FrigateCapability
import app.opah.tv.data.model.FrigateCapabilityAvailability
import app.opah.tv.data.model.FrigateCapabilityEvidence
import app.opah.tv.data.model.FrigateFeature
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.ExportIncident
import app.opah.tv.data.model.LiveStreamOption
import app.opah.tv.data.model.MotionSearchJobState
import app.opah.tv.data.model.MotionSearchResult
import app.opah.tv.data.model.RecordingStorageSummary
import app.opah.tv.data.model.RecordingStorageVolume
import app.opah.tv.data.model.RecordingSegment
import app.opah.tv.data.model.RecordingExport
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewCounts
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.model.ReviewSummaryMetadata
import app.opah.tv.data.model.SearchEvent
import app.opah.tv.data.model.SavedCameraView
import app.opah.tv.data.model.ServerVersionCompatibility
import app.opah.tv.data.model.StreamMetadata
import app.opah.tv.data.model.TemperatureReading
import app.opah.tv.data.model.VideoCodec
import app.opah.tv.data.network.PtzConnectionState
import app.opah.tv.data.network.PtzConnectionStatus
import app.opah.tv.playback.PlaybackKind
import app.opah.tv.playback.PlaybackRequest
import app.opah.tv.playback.BIRDSEYE_STRETCH_PREFERENCE_KEY

internal const val DOCUMENTATION_URI_PREFIX = "documentation://"

internal interface DocumentationResourceProvider {
    fun drawable(resourceName: String): Int
}

internal object DocumentationResources {
    private val provider: DocumentationResourceProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        check(app.opah.tv.BuildConfig.DOCUMENTATION_MODE) {
            "Documentation resources are unavailable in production builds"
        }
        val providerClass = Class.forName(
            "app.opah.tv.ui.DocumentationResourceProviderImpl",
        )
        providerClass.getDeclaredConstructor().newInstance() as DocumentationResourceProvider
    }

    fun drawable(resourceName: String): Int = provider.drawable(resourceName)
}

internal object DocumentationFixtures {
    private val profile = ConnectionProfile(
        apiBaseUrl = "https://frigate.example.test",
        username = "demo_viewer",
        rtspHostOverride = "streams.example.test",
        rtspPort = 8554,
    )

    private val cameraDefinitions = listOf(
        Triple("entry", "Front Entry", VideoCodec.AVC),
        Triple("garden", "Garden", VideoCodec.AVC),
        Triple("driveway", "Driveway", VideoCodec.HEVC),
        Triple("side", "Side Door", VideoCodec.AVC),
    )

    private val cameras = cameraDefinitions.mapIndexed { index, (name, displayName, codec) ->
        Camera(
            name = name,
            displayName = displayName,
            order = index,
            streams = listOf(
                LiveStreamOption(
                    label = "Main",
                    streamName = "${name}_main",
                    metadata = StreamMetadata(
                        streamName = "${name}_main",
                        available = true,
                        videoCodec = codec,
                        audioCodec = AudioCodec.AAC,
                        width = if (name == "driveway") 3840 else 2560,
                        height = if (name == "driveway") 2160 else 1440,
                        evidence = listOf("Documentation fixture"),
                    ),
                ),
                LiveStreamOption(
                    label = "Low bandwidth",
                    streamName = "${name}_sub",
                    metadata = StreamMetadata(
                        streamName = "${name}_sub",
                        available = true,
                        videoCodec = VideoCodec.AVC,
                        audioCodec = AudioCodec.NONE,
                        width = 1280,
                        height = 720,
                        evidence = listOf("Documentation fixture"),
                    ),
                ),
            ),
        )
    }

    private val reviewItems = listOf(
        reviewItem("review-entry", "entry", 1_787_157_300.0, ReviewSeverity.ALERT, listOf("person"), listOf("porch")),
        reviewItem("review-driveway", "driveway", 1_787_153_900.0, ReviewSeverity.ALERT, listOf("car"), listOf("driveway")),
        reviewItem("review-garden", "garden", 1_787_147_200.0, ReviewSeverity.DETECTION, listOf("dog"), listOf("yard")),
        reviewItem("review-entry-2", "entry", 1_787_139_800.0, ReviewSeverity.ALERT, listOf("package"), listOf("porch")),
        reviewItem("review-driveway-2", "driveway", 1_787_131_100.0, ReviewSeverity.DETECTION, listOf("bicycle"), listOf("driveway")),
        reviewItem("review-garden-2", "garden", 1_787_121_800.0, ReviewSeverity.ALERT, listOf("cat"), listOf("yard")),
    )

    private val briefingSummary = requireNotNull(
        BriefingSummarizer.summarize(
            candidates = reviewItems.take(3).map { item ->
                BriefingStoredCandidate(item, BriefingSummarizer.contentVersion(item))
            },
            acknowledgements = emptyMap(),
            capped = false,
            maximumPresented = 3,
        ),
    )

    private val incidents = listOf(
        ExportIncident(
            id = "incident-arrival",
            name = "Evening arrival",
            description = "Front entry and driveway clips from the same moment",
            createdAt = 1_787_157_420.0,
            updatedAt = 1_787_157_480.0,
        ),
    )

    private val savedRecordings = listOf(
        RecordingExport(
            id = "saved-entry",
            camera = "entry",
            name = "Front Entry package",
            createdAt = 1_787_157_420.0,
            videoPath = "/exports/saved-entry.mp4",
            thumbnailPath = "/media/frigate/clips/export/saved-entry.webp",
            inProgress = false,
            incidentId = incidents.first().id,
        ),
        RecordingExport(
            id = "saved-driveway",
            camera = "driveway",
            name = "Driveway visitor",
            createdAt = 1_787_157_445.0,
            videoPath = "/exports/saved-driveway.mp4",
            thumbnailPath = "/media/frigate/clips/export/saved-driveway.webp",
            inProgress = false,
            incidentId = incidents.first().id,
        ),
    )

    private val stressSavedRecordings = savedRecordings + (1..8).map { index ->
        val camera = cameras[index % cameras.size]
        RecordingExport(
            id = "saved-stress-$index",
            camera = camera.name,
            name = "Saved activity $index",
            createdAt = 1_787_157_000.0 - index * 180.0,
            videoPath = "/exports/saved-stress-$index.mp4",
            thumbnailPath = "/media/frigate/clips/export/saved-stress-$index.webp",
            inProgress = false,
        )
    }

    private val streamMetadata = cameras
        .flatMap(Camera::streams)
        .mapNotNull(LiveStreamOption::metadata)
        .associateBy(StreamMetadata::streamName) + (
        "birdseye" to StreamMetadata(
            streamName = "birdseye",
            available = true,
            videoCodec = VideoCodec.AVC,
            audioCodec = AudioCodec.NONE,
            width = 1920,
            height = 1080,
            evidence = listOf("Documentation fixture"),
        )
        )

    private val snapshot = DiscoverySnapshot(
        frigateVersion = "0.18.0-rc1",
        user = FrigateUserProfile(
            username = profile.username,
            role = "admin",
            allowedCameras = cameras.map(Camera::name).toSet(),
        ),
        cameras = cameras,
        streamMetadata = streamMetadata,
        recentReviewItems = reviewItems.take(3),
        birdseye = BirdseyeStatus(
            enabled = true,
            restreamConfigured = true,
            streamAvailable = true,
            streamName = "birdseye",
        ),
        versionCompatibility = ServerVersionCompatibility.SUPPORTED,
        capabilities = FrigateCapabilities(
            FrigateFeature.entries.associateWith {
                FrigateCapability(
                    FrigateCapabilityAvailability.AVAILABLE,
                    FrigateCapabilityEvidence.VALIDATED_API,
                )
            },
        ),
        ptzCameras = mapOf(
            "driveway" to CameraPtzInfo(
                cameraName = "driveway",
                features = setOf("pt", "zoom"),
                presets = listOf("home", "street"),
            ),
        ),
        cameraGroups = listOf(
            CameraGroup(
                name = "outside",
                displayName = "Outside",
                cameraNames = listOf("entry", "garden", "driveway", "side"),
                order = 0,
            ),
        ),
    )

    private val device = DeviceDiagnostics(
        manufacturer = "Reference",
        model = "Android TV",
        device = "documentation",
        androidRelease = "14",
        apiLevel = 34,
        codecs = listOf(
            codecCapability("H.264 / AVC", "video/avc", "Reference AVC hardware decoder"),
            codecCapability("H.265 / HEVC", "video/hevc", "Reference HEVC hardware decoder"),
        ),
    )

    private val information = FrigateInformationSummary(
        performance = FrigatePerformanceSummary(
            version = "0.18.0-rc1",
            uptimeSeconds = 432_845.0,
            cameraFps = 45.0,
            processFps = 15.0,
            detectionFps = 6.4,
            skippedFps = 0.0,
            systemCpuPercent = 38.2,
            frigateCpuPercent = 164.0,
            frigateMemoryPercent = 27.6,
            detectors = listOf(DetectorPerformance("coral", 7.82)),
            accelerators = listOf(AcceleratorPerformance("Integrated GPU", "GPU", 31.0, 22.0)),
            cameras = listOf(
                CameraPerformance("entry", "Front Entry", 15.0, 5.0, 2.1, 0.0),
                CameraPerformance("garden", "Garden", 15.0, 5.0, 1.8, 0.0),
                CameraPerformance("driveway", "Driveway", 15.0, 5.0, 2.5, 0.0),
            ),
            temperatures = listOf(TemperatureReading("System", 51.4), TemperatureReading("GPU", 47.8)),
        ),
        storage = RecordingStorageSummary(
            volume = RecordingStorageVolume(
                totalMiB = 512_000.0,
                usedMiB = 286_720.0,
                freeMiB = 225_280.0,
            ),
            cameras = listOf(
                CameraStorageUsage("entry", "Front Entry", 42_500.0, 8.30, 1_180.0),
                CameraStorageUsage("garden", "Garden", 56_900.0, 11.11, 1_620.0),
                CameraStorageUsage("driveway", "Driveway", 91_300.0, 17.83, 2_460.0),
            ),
            allCameraUsageMiB = 190_700.0,
            otherUsageMiB = 96_020.0,
        ),
    )

    fun state(rawScenario: String?): Phase0UiState {
        val scenario = rawScenario?.uppercase().orEmpty()
        val base = connectedState()
        return when (scenario) {
            "CONNECTING" -> Phase0UiState(
                loading = true,
                statusMessage = "Connecting…",
                savedProfile = profile,
                settings = base.settings,
            )
            "SETUP" -> Phase0UiState(
                loading = false,
                statusMessage = "Sign in to Frigate",
                savedProfile = profile.copy(rtspHostOverride = null, rtspPort = 8554),
                settings = base.settings,
            )
            "RECOVERY" -> Phase0UiState(
                loading = false,
                statusMessage = "Frigate is unavailable",
                errorMessage = "The demonstration server could not be reached",
                savedProfile = profile,
                settings = base.settings,
                savedSessionRecoveryAvailable = true,
            )
            "REVIEW_DETAIL" -> base.copy(
                review = base.review.copy(
                    selectedItemId = reviewItems.first().id,
                    recordingState = ReviewRecordingState.AVAILABLE,
                ),
            )
            "SAVED_DETAIL" -> base.copy(
                exports = base.exports.copy(selectedItemId = savedRecordings.first().id),
            )
            "PTZ_CONTROLS" -> base.copy(
                ptz = PtzUiState(
                    cameraName = "driveway",
                    connection = PtzConnectionState(PtzConnectionStatus.CONNECTED),
                ),
            )
            "ACTIVITY_SEARCH" -> base.copy(
                activitySearch = ActivitySearchState(
                    query = "car",
                    results = searchEvents("car", cameras.map(Camera::name).toSet()),
                    searchedOnce = true,
                ),
            )
            "ACTIVITY_SEARCH_LONG" -> base.copy(
                activitySearch = ActivitySearchState(
                    query = "a",
                    results = searchEvents("a", cameras.map(Camera::name).toSet()),
                    searchedOnce = true,
                ),
            )
            "ACTIVITY_MOTION" -> base.copy(
                motionReview = MotionReviewUiState(
                    cameraName = cameras.first().name,
                    regionIndex = 4,
                    jobState = MotionSearchJobState.SUCCESS,
                    results = listOf(
                        MotionSearchResult(1_787_157_420.0, 12.0),
                        MotionSearchResult(1_787_156_610.0, 8.0),
                        MotionSearchResult(1_787_155_980.0, 5.0),
                    ),
                    searchedOnce = true,
                ),
            )
            "CLIPS_LONG" -> base.copy(
                exports = base.exports.copy(items = stressSavedRecordings),
            )
            "UPDATE_LONG" -> base.copy(
                appUpdate = base.appUpdate.copy(
                    latestVersion = "0.5.0",
                    releaseNotes = (1..36).joinToString("\n") { index ->
                        "Improvement $index makes everyday TV navigation clearer"
                    },
                ),
            )
            "LIVE_PLAYBACK" -> base.copy(
                playback = livePlayback(cameras.first()),
                activeCameraName = cameras.first().name,
            )
            "RECORDED_PLAYBACK" -> base.copy(playback = recordedPlayback(reviewItems.first()))
            "DUAL_VIEW", "CAMERA_GROUP" -> base.copy(
                cameraGroupView = CameraGroupViewUiState(
                    title = "Outside",
                    streams = cameras.map { camera ->
                        CameraGroupStream(camera, "$DOCUMENTATION_URI_PREFIX${camera.name}")
                    },
                ),
            )
            "CUSTOM_THEME" -> base.copy(
                settings = base.settings.copy(
                    appearanceMode = AppearanceMode.CUSTOM,
                    customThemeColors = CustomThemeColors(
                        accentArgb = 0xFF5ED4C6.toInt(),
                        backgroundArgb = 0xFF0B1730.toInt(),
                    ),
                ),
            )
            else -> base
        }
    }

    fun reviewItems(): List<ReviewItem> = reviewItems

    fun information(): FrigateInformationSummary = information

    fun livePlayback(camera: Camera): PlaybackRequest = PlaybackRequest(
        title = camera.displayName,
        uri = "$DOCUMENTATION_URI_PREFIX${camera.name}",
        kind = PlaybackKind.LIVE,
        cameraName = camera.name,
        detail = "Main • Automatic stream selection",
    )

    fun compatibilityPlayback(camera: Camera): PlaybackRequest = livePlayback(camera).copy(
        detail = "Testing camera compatibility",
        compatibilityTest = true,
    )

    fun birdseyePlayback(): PlaybackRequest = PlaybackRequest(
        title = "Birdseye",
        uri = "${DOCUMENTATION_URI_PREFIX}birdseye",
        kind = PlaybackKind.LIVE,
        detail = "Frigate composite • Single RTSP stream",
        stretchPreferenceKey = BIRDSEYE_STRETCH_PREFERENCE_KEY,
    )

    fun recordedPlayback(item: ReviewItem): PlaybackRequest = PlaybackRequest(
        title = "Alert — ${cameraDisplayName(item.camera)}",
        uri = "$DOCUMENTATION_URI_PREFIX${item.camera}",
        kind = PlaybackKind.RECORDED,
        cameraName = item.camera,
        detail = "Recording",
        activityItemId = item.id,
        recordingStartTime = item.startTime,
        recordingEndTime = item.endTime,
    )

    fun recordingHistory(hourStartSeconds: Double, endSeconds: Double): List<RecordingSegment> = listOf(
        RecordingSegment(hourStartSeconds + 60.0, (hourStartSeconds + 14 * 60.0).coerceAtMost(endSeconds)),
        RecordingSegment(hourStartSeconds + 17 * 60.0, (hourStartSeconds + 44 * 60.0).coerceAtMost(endSeconds)),
        RecordingSegment(hourStartSeconds + 46 * 60.0, endSeconds),
    ).filter { it.endTime > it.startTime }

    fun searchEvents(query: String, cameras: Set<String>): List<SearchEvent> {
        val words = query.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        return reviewItems.map { item ->
            val description = when (item.id) {
                "review-driveway" -> "Red car arriving in the driveway"
                "review-entry" -> "Person walking to the front door"
                "review-garden" -> "Dog running through the yard"
                else -> item.objects.joinToString(" ")
            }
            SearchEvent(
                id = item.id,
                camera = item.camera,
                label = item.objects.firstOrNull() ?: "activity",
                subLabel = item.subLabels.firstOrNull(),
                zones = item.zones,
                startTime = item.startTime,
                endTime = item.endTime,
                description = description,
                recognizedLicensePlate = item.linkedEvents.firstOrNull()?.recognizedLicensePlate,
            )
        }.filter { event ->
            event.camera in cameras && words.all { word ->
                listOfNotNull(event.label, event.subLabel, event.description)
                    .plus(event.zones)
                    .any { it.contains(word, ignoreCase = true) }
            }
        }
    }

    fun reviewItemForEvent(event: SearchEvent): ReviewItem? = reviewItems.firstOrNull { it.id == event.id }

    fun historyPlayback(camera: Camera, startTime: Double, endTime: Double): PlaybackRequest = PlaybackRequest(
        title = camera.displayName,
        uri = "$DOCUMENTATION_URI_PREFIX${camera.name}?start=$startTime&end=$endTime",
        kind = PlaybackKind.RECORDED,
        cameraName = camera.name,
        detail = "Earlier recording",
        recordingStartTime = startTime,
        recordingEndTime = endTime,
    )

    fun searchPlayback(camera: Camera, event: SearchEvent): PlaybackRequest = PlaybackRequest(
        title = "${event.label.replace('_', ' ').replaceFirstChar(Char::uppercase)} at ${camera.displayName}",
        uri = "$DOCUMENTATION_URI_PREFIX${camera.name}?event=${event.id}",
        kind = PlaybackKind.RECORDED,
        cameraName = camera.name,
        detail = "Search result",
        recordingStartTime = event.startTime,
        recordingEndTime = event.endTime,
    )

    private fun connectedState(): Phase0UiState = Phase0UiState(
        loading = false,
        statusMessage = "Connected",
        savedProfile = profile,
        activeProfile = profile,
        snapshot = snapshot,
        recentActivityLoaded = true,
        device = device,
        settings = AppSettings(
            appearanceMode = AppearanceMode.DARK,
            diagnosticsEnabled = true,
            savedCameraViews = listOf(
                SavedCameraView(
                    id = "front-and-driveway",
                    name = "Front and driveway",
                    firstCameraName = "entry",
                    secondCameraName = "driveway",
                ),
            ),
        ),
        settingsLoaded = true,
        review = ReviewBrowserState(
            items = reviewItems.filter { it.severity == ReviewSeverity.ALERT },
            knownLabels = reviewItems.flatMap(ReviewItem::objects).toSet(),
            knownZones = reviewItems.flatMap(ReviewItem::zones).toSet(),
            counts = ReviewCounts(
                reviewedAlerts = reviewItems.count { it.severity == ReviewSeverity.ALERT && it.hasBeenReviewed },
                reviewedDetections = reviewItems.count {
                    it.severity == ReviewSeverity.DETECTION && it.hasBeenReviewed
                },
                totalAlerts = reviewItems.count { it.severity == ReviewSeverity.ALERT },
                totalDetections = reviewItems.count { it.severity == ReviewSeverity.DETECTION },
            ),
            loadedOnce = true,
        ),
        information = InformationUiState(
            loadedOnce = true,
            summary = information,
        ),
        exports = ExportsUiState(
            loadedOnce = true,
            items = savedRecordings,
            incidents = incidents,
            incidentsLoaded = true,
            selectedIncidentId = incidents.first().id,
        ),
        appUpdate = AppUpdateUiState(
            checkedOnce = true,
            updateAvailable = false,
            latestVersion = "0.5.0",
        ),
        privacy = PrivacyUiState(
            loading = false,
            available = true,
            epoch = 1L,
        ),
        tvAlerts = TvAlertsUiState(
            loading = false,
            available = true,
        ),
        briefing = BriefingUiState(
            summary = briefingSummary,
            audience = BriefingAudience.OWNER,
            privacyEpoch = 1L,
        ),
    )

    private fun reviewItem(
        id: String,
        camera: String,
        startTime: Double,
        severity: ReviewSeverity,
        objects: List<String>,
        zones: List<String>,
    ): ReviewItem {
        val recognizedName = "Alex".takeIf { id == "review-entry" }
        val event = SearchEvent(
            id = id,
            camera = camera,
            label = objects.firstOrNull() ?: "activity",
            subLabel = recognizedName,
            zones = zones,
            startTime = startTime,
            endTime = startTime + 28.0,
            description = "${objects.firstOrNull().orEmpty()} near ${zones.firstOrNull().orEmpty()}",
            recognizedLicensePlate = "OPAH 300".takeIf { id == "review-driveway" },
        )
        return ReviewItem(
            id = id,
            camera = camera,
            startTime = startTime,
            endTime = startTime + 28.0,
            severity = severity,
            thumbnailPath = null,
            objects = objects,
            zones = zones,
            hasBeenReviewed = id.endsWith("2"),
            recordingAvailable = true,
            detectionIds = listOf(id),
            subLabels = listOfNotNull(recognizedName),
            summary = ReviewSummaryMetadata(
                title = when (id) {
                    "review-entry" -> "Visitor at the front door"
                    "review-driveway" -> "Car arrived in the driveway"
                    else -> null
                },
                shortSummary = "Activity was recorded near ${zones.firstOrNull().orEmpty()}",
                potentialThreatLevel = 0,
            ),
            linkedEvents = listOf(event),
        )
    }

    private fun codecCapability(label: String, mimeType: String, decoderName: String) = CodecCapability(
        label = label,
        mimeType = mimeType,
        decoders = listOf(
            DecoderCapability(
                name = decoderName,
                mimeType = mimeType,
                hardwareAccelerated = true,
                softwareOnly = false,
                vendor = true,
                adaptivePlayback = true,
            ),
        ),
    )

    private fun cameraDisplayName(cameraName: String): String =
        cameras.firstOrNull { it.name == cameraName }?.displayName ?: cameraName
}

internal class DocumentationImageStore(context: Context) {
    private val resources = context.resources
    private val cameraImages = mutableMapOf<String, CameraImage>()

    fun camera(cameraName: String): CameraImage? = cameraImages.getOrPut(cameraName) {
        val resourceName = when (cameraName) {
            "entry" -> "docs_camera_entry"
            "garden" -> "docs_camera_garden"
            "driveway" -> "docs_camera_driveway"
            "birdseye" -> "docs_camera_birdseye"
            else -> "docs_camera_entry"
        }
        val resourceId = DocumentationResources.drawable(resourceName)
        CameraImage(
            bitmap = requireNotNull(BitmapFactory.decodeResource(resources, resourceId)),
            loadedAtMillis = System.currentTimeMillis(),
        )
    }

    fun review(item: ReviewItem): ReviewImage? = camera(item.camera)?.let {
        ReviewImage(bitmap = it.bitmap, loadedAtMillis = it.loadedAtMillis)
    }

    fun export(item: RecordingExport): ReviewImage? = camera(item.camera)?.let {
        ReviewImage(bitmap = it.bitmap, loadedAtMillis = it.loadedAtMillis)
    }
}
