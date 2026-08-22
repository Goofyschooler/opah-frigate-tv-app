package app.opah.tv.data

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.DiscoverySnapshot
import app.opah.tv.data.model.EventSearchQuery
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.FrigateInformationSummary
import app.opah.tv.data.model.CameraStorageUsage
import app.opah.tv.data.model.CameraPtzInfo
import app.opah.tv.data.model.RecordingStorageSummary
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.model.ReviewCounts
import app.opah.tv.data.model.RecordingHourSummary
import app.opah.tv.data.model.MotionActivity
import app.opah.tv.data.model.RecordingSegment
import app.opah.tv.data.model.RecordingExport
import app.opah.tv.data.model.RecordingExportStart
import app.opah.tv.data.model.SearchEvent
import app.opah.tv.data.model.ServerVersionInfo
import app.opah.tv.data.network.FrigateGateway
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class DiscoveryBootstrap(
    internal val config: String,
    internal val catalog: CameraCatalog,
    val snapshot: DiscoverySnapshot,
)

internal enum class ExportDeleteContract { SINGLE_017, BULK_018 }

internal fun exportDeleteContract(version: ServerVersionInfo): ExportDeleteContract =
    if (version.major == 0 && version.minor != null && version.minor >= 18) {
        ExportDeleteContract.BULK_018
    } else {
        ExportDeleteContract.SINGLE_017
    }

/** Coordinates foundation repositories into the snapshot consumed by the prototype UI. */
class FrigateRepository(
    private val api: FrigateGateway,
    private val parsers: FrigateJsonParsers,
    private val cameraRepository: CameraRepository = CameraRepository(parsers),
    private val streamRepository: StreamRepository = StreamRepository(api, parsers),
    private val reviewRepository: ReviewRepository = ReviewRepository(api, parsers),
    private val versionPolicy: FrigateVersionPolicy = FrigateVersionPolicy(),
    private val capabilityResolver: FrigateCapabilityResolver = FrigateCapabilityResolver(),
) {
    suspend fun refresh(profile: ConnectionProfile, user: FrigateUserProfile): DiscoverySnapshot =
        discover(profile, user)

    /**
     * Loads only the freshly authorized camera catalog needed to make Home usable.
     * Stream, Review, Birdseye availability, and version enrichment deliberately follow later.
     */
    suspend fun discoverEssential(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
    ): DiscoveryBootstrap {
        val config = api.getConfig(profile)
        val catalog = cameraRepository.catalog(config, user)
        val visibleCameraNames = catalog.cameras.mapTo(mutableSetOf()) { it.name }
        return DiscoveryBootstrap(
            config = config,
            catalog = catalog,
            snapshot = DiscoverySnapshot(
                frigateVersion = "Loading",
                user = user,
                cameras = catalog.cameras,
                streamMetadata = emptyMap(),
                recentReviewItems = emptyList(),
                birdseye = catalog.birdseye,
                authorizedCameraNames = parsers.parseAuthorizedCameraNames(config, user.allowedCameras),
                cameraGroups = parsers.parseCameraGroups(config, visibleCameraNames),
            ),
        )
    }

    suspend fun enrich(
        profile: ConnectionProfile,
        bootstrap: DiscoveryBootstrap,
    ): DiscoverySnapshot = coroutineScope {
        val versionDeferred = async { versionPolicy.evaluate(api.getVersion(profile)) }
        val streamDeferred = async { streamRepository.discover(profile, bootstrap.catalog) }
        val reviewDeferred = async {
            reviewRepository.recent(profile, bootstrap.snapshot.user.allowedCameras)
        }
        val ptzDeferred = async {
            discoverPtzCameras(
                profile = profile,
                configJson = bootstrap.config,
                allowedCameras = bootstrap.snapshot.user.allowedCameras,
            )
        }

        val version = versionDeferred.await()
        val streamDiscovery = streamDeferred.await()
        val reviewDiscovery = reviewDeferred.await()
        val ptzCameras = ptzDeferred.await()
        val warnings = buildList {
            version.warning?.let(::add)
            addAll(streamDiscovery.warnings)
            addAll(reviewDiscovery.warnings)
        }

        val birdseye = parsers.parseBirdseyeStatus(bootstrap.config, streamDiscovery.metadata)
        val cameras = parsers.parseCameras(
            bootstrap.config,
            streamDiscovery.metadata,
            bootstrap.snapshot.user.allowedCameras,
        )
        bootstrap.snapshot.copy(
            frigateVersion = version.rawVersion,
            cameras = cameras,
            streamMetadata = streamDiscovery.metadata,
            recentReviewItems = reviewDiscovery.items,
            birdseye = birdseye,
            warnings = warnings,
            versionCompatibility = version.compatibility,
            capabilities = capabilityResolver.resolve(
                version = version,
                configJson = bootstrap.config,
                allowedCameras = bootstrap.snapshot.user.allowedCameras,
                birdseye = birdseye,
                birdseyePermitted = bootstrap.catalog.fullCameraAccess,
                ptzCameras = ptzCameras,
            ),
            ptzCameras = ptzCameras,
            cameraGroups = parsers.parseCameraGroups(
                bootstrap.config,
                cameras.mapTo(mutableSetOf()) { it.name },
            ),
        )
    }

    private suspend fun discoverPtzCameras(
        profile: ConnectionProfile,
        configJson: String,
        allowedCameras: Set<String>,
    ): Map<String, CameraPtzInfo> = coroutineScope {
        parsers.parsePtzConfiguredCameraNames(configJson)
            .intersect(allowedCameras)
            .associateWith { camera ->
                async {
                    runCatching { parsers.parsePtzInfo(camera, api.getPtzInfo(profile, camera)) }
                        .getOrNull()
                        ?.takeIf(CameraPtzInfo::hasControls)
                }
            }
            .mapNotNull { (camera, deferred) -> deferred.await()?.let { camera to it } }
            .toMap()
    }

    fun reviewPlaybackUrl(profile: ConnectionProfile, item: ReviewItem): String =
        reviewRepository.playbackUrl(profile, item)

    suspend fun searchReview(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        query: ReviewSearchQuery,
        beforeBySeverity: Map<app.opah.tv.data.model.ReviewSeverity, Double> = emptyMap(),
    ): ReviewDiscovery = reviewRepository.search(profile, allowedCameras, query, beforeBySeverity)

    suspend fun reviewRecordingAvailable(
        profile: ConnectionProfile,
        item: ReviewItem,
    ): Result<Boolean> = reviewRepository.recordingAvailable(profile, item)

    suspend fun setReviewReviewed(
        profile: ConnectionProfile,
        item: ReviewItem,
        reviewed: Boolean,
    ) = reviewRepository.setReviewed(profile, item, reviewed)

    suspend fun setReviewsReviewed(
        profile: ConnectionProfile,
        items: Collection<ReviewItem>,
        reviewed: Boolean,
    ) = reviewRepository.setReviewed(profile, items, reviewed)

    suspend fun loadReviewCounts(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
    ): ReviewCounts = reviewRepository.counts(profile, allowedCameras)

    suspend fun enrichReviewItem(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        item: ReviewItem,
    ): ReviewItem = reviewRepository.enrich(profile, allowedCameras, item)

    suspend fun loadRecordingHistory(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        camera: String,
        after: Double,
        before: Double,
    ): List<RecordingSegment> {
        require(camera in allowedCameras) { "Camera is not permitted." }
        require(before > after) { "Recording range end must follow its start." }
        return parsers.parseRecordingSegments(api.getRecordings(profile, camera, after, before))
    }

    suspend fun loadRecordingHourSummaries(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        camera: String,
        timezone: String,
    ): List<RecordingHourSummary> {
        require(camera in allowedCameras) { "Camera is not permitted." }
        return parsers.parseRecordingHourSummaries(api.getRecordingSummary(profile, camera, timezone))
    }

    suspend fun loadMotionActivity(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        camera: String,
        after: Double,
        before: Double,
    ): List<MotionActivity> {
        require(camera in allowedCameras) { "Camera is not permitted." }
        return parsers.parseMotionActivity(
            api.getReviewMotionActivity(profile, setOf(camera), after, before),
        ).filter { it.camera == camera }
    }

    suspend fun searchEvents(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        query: EventSearchQuery,
    ): List<SearchEvent> {
        val cameras = query.cameras.intersect(allowedCameras)
        if (cameras.isEmpty()) return emptyList()
        return parsers.parseSearchEvents(api.searchEvents(profile, query.copy(cameras = cameras)))
            .filter { it.camera in cameras }
    }

    fun recordingPlaybackUrl(
        profile: ConnectionProfile,
        camera: String,
        startTime: Double,
        endTime: Double,
    ): String = api.recordingPlaybackUrl(profile, camera, startTime, endTime)

    suspend fun loadExports(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
    ): List<RecordingExport> = parsers.parseRecordingExports(
        api.getExports(profile),
        allowedCameras,
    )

    suspend fun saveReviewClip(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        item: ReviewItem,
        name: String,
    ): RecordingExportStart {
        require(item.camera in allowedCameras) { "Camera is not permitted." }
        val start = (item.startTime - EXPORT_PADDING_SECONDS).coerceAtLeast(0.0)
        val end = (item.endTime ?: (System.currentTimeMillis() / 1000.0)) + EXPORT_PADDING_SECONDS
        return parsers.parseRecordingExportStart(
            api.startRecordingExport(profile, item.camera, start, end, name),
        )
    }

    suspend fun saveRecordingClip(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        camera: String,
        startTime: Double,
        endTime: Double,
        name: String,
    ): RecordingExportStart {
        require(camera in allowedCameras) { "Camera is not permitted." }
        require(endTime > startTime) { "Recording end must follow its start." }
        return parsers.parseRecordingExportStart(
            api.startRecordingExport(profile, camera, startTime, endTime, name),
        )
    }

    fun exportPlaybackUrl(
        profile: ConnectionProfile,
        export: RecordingExport,
    ): String? = api.exportPlaybackUrl(profile, export)

    suspend fun deleteExport(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        export: RecordingExport,
        frigateVersion: String,
    ) {
        require(export.camera in allowedCameras) { "Camera is not permitted." }
        val version = versionPolicy.evaluate(frigateVersion)
        when (exportDeleteContract(version)) {
            ExportDeleteContract.BULK_018 -> api.deleteExports(profile, setOf(export.id))
            ExportDeleteContract.SINGLE_017 -> api.deleteExport(profile, export.id)
        }
    }

    suspend fun loadRecordingStorage(
        profile: ConnectionProfile,
        snapshot: DiscoverySnapshot,
    ): RecordingStorageSummary = coroutineScope {
        val statsDeferred = async { api.getStats(profile) }
        val storageDeferred = async { api.getRecordingsStorage(profile) }
        createStorageSummary(
            statsJson = statsDeferred.await(),
            storageJson = storageDeferred.await(),
            snapshot = snapshot,
        )
    }

    suspend fun loadInformation(
        profile: ConnectionProfile,
        snapshot: DiscoverySnapshot,
    ): FrigateInformationSummary = coroutineScope {
        val statsDeferred = async { api.getStats(profile) }
        val storageDeferred = async { api.getRecordingsStorage(profile) }
        val statsJson = statsDeferred.await()
        FrigateInformationSummary(
            performance = parsers.parsePerformanceSummary(
                rawJson = statsJson,
                fallbackVersion = snapshot.frigateVersion,
                authorizedCameraNames = snapshot.authorizedCameraNames,
            ),
            storage = createStorageSummary(statsJson, storageDeferred.await(), snapshot),
        )
    }

    private fun createStorageSummary(
        statsJson: String,
        storageJson: String,
        snapshot: DiscoverySnapshot,
    ): RecordingStorageSummary {
        val volume = parsers.parseRecordingStorageVolume(statsJson)
            ?: error("Frigate did not return recording storage totals.")
        val samples = parsers.parseCameraStorageSamples(storageJson)
        val samplesByLabel = samples.associateBy { normalizeStorageLabel(it.serverLabel) }
        val visible = snapshot.authorizedCameraNames.map { (cameraName, displayName) ->
            val sample = sequenceOf(displayName, cameraName)
                .map(::normalizeStorageLabel)
                .mapNotNull(samplesByLabel::get)
                .firstOrNull()
            val usage = sample?.usageMiB ?: 0.0
            CameraStorageUsage(
                cameraName = cameraName,
                displayName = displayName,
                usageMiB = usage,
                percentageOfTotal = if (volume.totalMiB > 0.0) usage / volume.totalMiB * 100 else 0.0,
                bandwidthMiBPerHour = sample?.bandwidthMiBPerHour ?: 0.0,
            )
        }
        val allCameraUsage = samples.sumOf(FrigateJsonParsers.CameraStorageSample::usageMiB)
        return RecordingStorageSummary(
            volume = volume,
            cameras = visible.sortedWith(
                compareByDescending<CameraStorageUsage>(CameraStorageUsage::usageMiB)
                    .thenBy(CameraStorageUsage::displayName),
            ),
            allCameraUsageMiB = allCameraUsage,
            otherUsageMiB = (volume.usedMiB - allCameraUsage).coerceAtLeast(0.0),
        )
    }

    suspend fun discover(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
    ): DiscoverySnapshot = enrich(profile, discoverEssential(profile, user))

    private fun normalizeStorageLabel(value: String): String =
        value.trim().lowercase().replace('_', ' ')

    private companion object {
        const val EXPORT_PADDING_SECONDS = 8.0
    }
}
