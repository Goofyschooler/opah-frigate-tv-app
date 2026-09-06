package app.opah.tv.data

import app.opah.tv.data.model.AudioCodec
import app.opah.tv.data.model.BirdseyeStatus
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.CameraGroup
import app.opah.tv.data.model.CameraPtzInfo
import app.opah.tv.data.model.AcceleratorPerformance
import app.opah.tv.data.model.CameraPerformance
import app.opah.tv.data.model.DetectorPerformance
import app.opah.tv.data.model.FrigatePerformanceSummary
import app.opah.tv.data.model.RecordingStorageVolume
import app.opah.tv.data.model.LiveStreamOption
import app.opah.tv.data.model.MotionActivity
import app.opah.tv.data.model.RecordingHourSummary
import app.opah.tv.data.model.RecordingSegment
import app.opah.tv.data.model.RecordingExport
import app.opah.tv.data.model.RecordingExportStart
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewCounts
import app.opah.tv.data.model.ReviewLifecycle
import app.opah.tv.data.model.ReviewSummaryMetadata
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.model.RealtimeReviewItem
import app.opah.tv.data.model.RealtimeReviewUpdate
import app.opah.tv.data.model.SearchEvent
import app.opah.tv.data.model.StreamMetadata
import app.opah.tv.data.model.TemperatureReading
import app.opah.tv.data.model.VideoCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FrigateJsonParsers(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    data class CameraStorageSample(
        val serverLabel: String,
        val usageMiB: Double,
        val bandwidthMiBPerHour: Double,
    )

    fun parseConfiguredCameraNames(configJson: String): Set<String> {
        val root = json.parseToJsonElement(configJson).jsonObject
        return root.obj("cameras")?.keys.orEmpty()
    }

    fun parsePtzConfiguredCameraNames(configJson: String): Set<String> {
        val cameras = json.parseToJsonElement(configJson).jsonObject.obj("cameras")
            ?: return emptySet()
        return cameras.mapNotNull { (name, value) ->
            val camera = value as? JsonObject ?: return@mapNotNull null
            val onvif = camera.obj("onvif") ?: return@mapNotNull null
            name.takeIf { !onvif.string("host").isNullOrBlank() }
        }.toSet()
    }

    fun parsePtzInfo(cameraName: String, rawJson: String): CameraPtzInfo? {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return null
        val returnedName = root.string("name")?.takeIf(String::isNotBlank) ?: return null
        if (returnedName != cameraName) return null
        val features = root.stringList("features")
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        val presets = root.stringList("presets")
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        return CameraPtzInfo(returnedName, features, presets)
    }

    fun parseAuthorizedCameraNames(
        configJson: String,
        allowedCameras: Set<String>,
    ): Map<String, String> {
        val cameras = json.parseToJsonElement(configJson).jsonObject.obj("cameras")
            ?: return emptyMap()
        return cameras.mapNotNull { (name, value) ->
            if (name !in allowedCameras) return@mapNotNull null
            val camera = value as? JsonObject ?: return@mapNotNull null
            name to (camera.string("friendly_name")?.takeIf(String::isNotBlank) ?: humanize(name))
        }.toMap()
    }

    fun parseCameraGroups(
        configJson: String,
        visibleCameraNames: Set<String>,
    ): List<CameraGroup> {
        val groups = json.parseToJsonElement(configJson).jsonObject.obj("camera_groups")
            ?: return emptyList()
        return groups.mapNotNull { (name, element) ->
            val group = element as? JsonObject ?: return@mapNotNull null
            val cameraNames = when (val cameras = group["cameras"]) {
                is JsonArray -> cameras.mapNotNull { it.jsonPrimitive.contentOrNull }
                else -> (cameras as? kotlinx.serialization.json.JsonPrimitive)
                    ?.contentOrNull
                    ?.let(::listOf)
                    .orEmpty()
            }.map(String::trim)
                .filter { it in visibleCameraNames }
                .distinct()
            if (cameraNames.size < 2) return@mapNotNull null
            CameraGroup(
                name = name,
                displayName = humanize(name).replaceFirstChar(Char::uppercase),
                cameraNames = cameraNames,
                order = group.int("order") ?: Int.MAX_VALUE,
            )
        }.sortedWith(compareBy<CameraGroup> { it.order }.thenBy { it.displayName })
    }

    fun parseCameras(
        configJson: String,
        streamMetadata: Map<String, StreamMetadata>,
        allowedCameras: Set<String>? = null,
    ): List<Camera> {
        val root = json.parseToJsonElement(configJson).jsonObject
        val configuredGo2RtcStreams = root.obj("go2rtc")?.obj("streams")?.keys.orEmpty()
        val cameraObject = root.obj("cameras") ?: return emptyList()

        return cameraObject.entries.mapNotNull { (name, element) ->
            val camera = element as? JsonObject ?: return@mapNotNull null
            if (camera.bool("enabled") == false) return@mapNotNull null
            if (allowedCameras != null && name !in allowedCameras) return@mapNotNull null

            val liveStreams = camera.obj("live")?.obj("streams")
            val options = liveStreams?.entries?.mapNotNull { (label, streamElement) ->
                val streamName = streamElement.jsonPrimitive.contentOrNull
                    ?: return@mapNotNull null
                LiveStreamOption(label, streamName, streamMetadata[streamName])
            }.orEmpty().ifEmpty {
                if (name in configuredGo2RtcStreams || name in streamMetadata) {
                    listOf(LiveStreamOption("Main", name, streamMetadata[name]))
                } else {
                    emptyList()
                }
            }

            Camera(
                name = name,
                displayName = camera.string("friendly_name")?.takeIf { it.isNotBlank() }
                    ?: humanize(name),
                order = camera.obj("ui")?.int("order") ?: Int.MAX_VALUE,
                streams = options,
            )
        }.filter { camera ->
            val raw = cameraObject[camera.name] as? JsonObject
            raw?.obj("ui")?.bool("dashboard") != false
        }.sortedWith(compareBy<Camera> { it.order }.thenBy { it.name })
    }

    fun parseGo2RtcStreams(rawJson: String): Map<String, StreamMetadata> {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return emptyMap()
        return root.mapValues { (streamName, element) ->
            val streamObject = element as? JsonObject
            val producers = streamObject?.get("producers") as? JsonArray
            val evidence = buildList { collectEvidence(element, false, this) }
                .distinct()
            val joined = evidence.joinToString("\n")
            val resolution = RESOLUTION.find(joined)

            StreamMetadata(
                streamName = streamName,
                available = producers?.isNotEmpty() == true,
                videoCodec = detectVideoCodec(joined),
                audioCodec = detectAudioCodec(joined, evidence),
                width = resolution?.groupValues?.getOrNull(1)?.toIntOrNull(),
                height = resolution?.groupValues?.getOrNull(2)?.toIntOrNull(),
                evidence = evidence.filterNot { it.contains("rtsp://", ignoreCase = true) }
                    .take(12),
            )
        }
    }

    fun parseSingleGo2RtcStream(streamName: String, rawJson: String): StreamMetadata? {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return null
        parseGo2RtcStreams(rawJson)[streamName]?.let { return it }
        val wrapped = JsonObject(mapOf(streamName to root))
        return parseGo2RtcStreams(wrapped.toString())[streamName]
    }

    fun parseReviewItems(rawJson: String): List<ReviewItem> {
        val array = json.parseToJsonElement(rawJson) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("id") ?: return@mapNotNull null
            val camera = item.string("camera") ?: return@mapNotNull null
            val start = item.double("start_time") ?: return@mapNotNull null
            val data = item.obj("data")
            val rawSeverity = item.string("severity")?.trim()?.lowercase()
            ReviewItem(
                id = id,
                camera = camera,
                startTime = start,
                endTime = item.double("end_time"),
                severity = rawSeverity.toReviewSeverity(),
                thumbnailPath = item.string("thumb_path"),
                objects = data.stringList("objects"),
                zones = data.stringList("zones"),
                hasBeenReviewed = item.bool("has_been_reviewed") ?: false,
                audio = data.stringList("audio"),
                detectionIds = data.stringList("detections"),
                subLabels = data.stringList("sub_labels"),
                summary = data?.obj("metadata").toReviewSummary(),
                rawSeverity = rawSeverity,
                significantMotionAreas = data.intList("significant_motion_areas"),
            )
        }
    }

    /**
     * Parses either Frigate's inner Review update or its double-encoded WebSocket envelope.
     * The real-time representation intentionally remains separate from the canonical REST item.
     */
    fun parseRealtimeReviewUpdate(rawJson: String): RealtimeReviewUpdate? {
        if (rawJson.length > MAX_REALTIME_REVIEW_JSON_CHARACTERS) return null
        val root = runCatching { json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
            ?: return null
        val update = when {
            root["type"] != null -> root
            root.string("topic") == "reviews" -> root.reviewPayload()
            else -> null
        } ?: return null
        val rawLifecycle = update.string("type")?.trim()?.lowercase()
        if (rawLifecycle != null && !rawLifecycle.isBoundedRealtimeText(MAX_REALTIME_RAW_VALUE_CHARACTERS)) {
            return null
        }
        val after = update.obj("after")?.toRealtimeReviewItem() ?: return null
        return RealtimeReviewUpdate(
            lifecycle = rawLifecycle.toReviewLifecycle(),
            rawLifecycle = rawLifecycle,
            before = update.obj("before")?.toRealtimeReviewItem(),
            after = after,
        )
    }

    fun parseReviewCounts(rawJson: String): ReviewCounts {
        val root = runCatching { json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
            ?: return ReviewCounts()
        val counts = root.obj("last24Hours") ?: return ReviewCounts()
        return ReviewCounts(
            reviewedAlerts = counts.int("reviewed_alert") ?: 0,
            reviewedDetections = counts.int("reviewed_detection") ?: 0,
            totalAlerts = counts.int("total_alert") ?: 0,
            totalDetections = counts.int("total_detection") ?: 0,
        )
    }

    fun parseSearchEvents(rawJson: String): List<SearchEvent> {
        val array = json.parseToJsonElement(rawJson) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val event = element as? JsonObject ?: return@mapNotNull null
            val id = event.string("id") ?: return@mapNotNull null
            val camera = event.string("camera") ?: return@mapNotNull null
            val label = event.string("label") ?: return@mapNotNull null
            val start = event.double("start_time") ?: return@mapNotNull null
            SearchEvent(
                id = id,
                camera = camera,
                label = label,
                subLabel = event.string("sub_label"),
                zones = event.stringList("zones"),
                startTime = start,
                endTime = event.double("end_time"),
                description = event.obj("data")?.string("description"),
                recognizedLicensePlate = event.string("recognized_license_plate")
                    ?: event.obj("data")?.string("recognized_license_plate"),
                recognizedLicensePlateScore = event.double("recognized_license_plate_score")
                    ?: event.obj("data")?.double("recognized_license_plate_score"),
                averageEstimatedSpeed = event.double("average_estimated_speed")
                    ?: event.obj("data")?.double("average_estimated_speed"),
                attributes = event.obj("data").stringList("attributes"),
                hasClip = event.bool("has_clip") ?: true,
            )
        }.distinctBy(SearchEvent::id)
    }

    fun parseRecordingHourSummaries(rawJson: String): List<RecordingHourSummary> {
        val days = runCatching { json.parseToJsonElement(rawJson) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return days.flatMap { element ->
            val day = element as? JsonObject ?: return@flatMap emptyList()
            val date = day.string("day") ?: return@flatMap emptyList()
            (day["hours"] as? JsonArray).orEmpty().mapNotNull { hourElement ->
                val hour = hourElement as? JsonObject ?: return@mapNotNull null
                RecordingHourSummary(
                    day = date,
                    hour = hour.string("hour")?.toIntOrNull() ?: hour.int("hour") ?: return@mapNotNull null,
                    durationSeconds = hour.int("duration") ?: 0,
                    motionSeconds = hour.double("motion") ?: 0.0,
                    objectSeconds = hour.double("objects") ?: 0.0,
                    eventCount = hour.int("events") ?: 0,
                )
            }
        }.sortedWith(compareByDescending<RecordingHourSummary> { it.day }.thenByDescending { it.hour })
    }

    fun parseMotionActivity(rawJson: String): List<MotionActivity> {
        val array = runCatching { json.parseToJsonElement(rawJson) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            MotionActivity(
                startTime = item.double("start_time") ?: return@mapNotNull null,
                motion = (item.double("motion") ?: 0.0).coerceAtLeast(0.0),
                camera = item.string("camera") ?: return@mapNotNull null,
            )
        }.sortedBy(MotionActivity::startTime)
    }

    fun parseRecordingSegments(rawJson: String): List<RecordingSegment> {
        val array = json.parseToJsonElement(rawJson) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val segment = element as? JsonObject ?: return@mapNotNull null
            val start = segment.double("start_time") ?: return@mapNotNull null
            val end = segment.double("end_time") ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            RecordingSegment(start, end)
        }.sortedBy(RecordingSegment::startTime)
    }

    fun parseRecordingStorageVolume(rawJson: String): RecordingStorageVolume? {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return null
        val storage = root.obj("service")?.obj("storage") ?: return null
        val recordings = (storage["/media/frigate/recordings"] as? JsonObject)
            ?: storage.entries.firstNotNullOfOrNull { (path, value) ->
                (value as? JsonObject)?.takeIf {
                    path.trimEnd('/').endsWith("/recordings") && it.double("total") != null
                }
            }
            ?: return null
        val total = recordings.double("total") ?: return null
        val used = recordings.double("used") ?: return null
        val free = recordings.double("free") ?: (total - used)
        if (total <= 0.0 || used < 0.0 || free < 0.0) return null
        return RecordingStorageVolume(total, used, free)
    }

    fun parseCameraStorageSamples(rawJson: String): List<CameraStorageSample> {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return emptyList()
        return root.mapNotNull { (label, value) ->
            val item = value as? JsonObject ?: return@mapNotNull null
            CameraStorageSample(
                serverLabel = label,
                usageMiB = (item.double("usage") ?: 0.0).coerceAtLeast(0.0),
                bandwidthMiBPerHour = (item.double("bandwidth") ?: 0.0).coerceAtLeast(0.0),
            )
        }
    }

    fun parseRecordingExports(
        rawJson: String,
        allowedCameras: Set<String>,
    ): List<RecordingExport> {
        val root = runCatching { json.parseToJsonElement(rawJson) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return root.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val camera = item.string("camera")?.takeIf { it in allowedCameras } ?: return@mapNotNull null
            val videoPath = item.string("video_path") ?: return@mapNotNull null
            val date = item.double("date") ?: return@mapNotNull null
            RecordingExport(
                id = id,
                camera = camera,
                name = item.string("name")?.takeIf(String::isNotBlank) ?: id,
                createdAt = date,
                videoPath = videoPath,
                thumbnailPath = item.string("thumb_path")?.takeIf(String::isNotBlank),
                inProgress = item.bool("in_progress") ?: false,
                incidentId = sequenceOf("export_case_id", "export_case")
                    .mapNotNull { key -> item.string(key) }
                    .firstOrNull(String::isNotBlank),
            )
        }.sortedByDescending(RecordingExport::createdAt)
    }

    fun parseRecordingExportStart(rawJson: String): RecordingExportStart {
        val root = json.parseToJsonElement(rawJson) as? JsonObject
            ?: error("Frigate did not return a valid saved clip response")
        if (root.bool("success") != true) {
            error(root.string("message") ?: "Frigate could not save this clip.")
        }
        val id = root.string("export_id")?.takeIf(String::isNotBlank)
            ?: error("Frigate did not return a saved clip ID")
        return RecordingExportStart(
            exportId = id,
            message = root.string("message").orEmpty(),
        )
    }

    fun parsePerformanceSummary(
        rawJson: String,
        fallbackVersion: String,
        authorizedCameraNames: Map<String, String>,
    ): FrigatePerformanceSummary {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: JsonObject(emptyMap())
        val service = root.obj("service")
        val cpuUsages = root.obj("cpu_usages")
        val fullSystem = cpuUsages?.get("frigate.full_system") as? JsonObject
        val cpuProcesses = cpuUsages.orEmpty().mapNotNull { (pid, value) ->
            if (pid.toLongOrNull() == null) return@mapNotNull null
            value as? JsonObject
        }
        val cameraStats = root.obj("cameras")
        val cameras = authorizedCameraNames.mapNotNull { (cameraName, displayName) ->
            val item = cameraStats?.get(cameraName) as? JsonObject ?: return@mapNotNull null
            CameraPerformance(
                cameraName = cameraName,
                displayName = displayName,
                cameraFps = item.number("camera_fps"),
                processFps = item.number("process_fps"),
                detectionFps = item.number("detection_fps"),
                skippedFps = item.number("skipped_fps"),
            )
        }.sortedBy(CameraPerformance::displayName)
        val detectors = root.obj("detectors").orEmpty().mapNotNull { (name, value) ->
            val item = value as? JsonObject ?: return@mapNotNull null
            DetectorPerformance(name = humanize(name), inferenceSpeedMs = item.number("inference_speed"))
        }.sortedBy(DetectorPerformance::name)
        val accelerators = buildList {
            addAll(parseAccelerators(root.obj("gpu_usages"), "GPU"))
            addAll(parseAccelerators(root.obj("npu_usages"), "NPU"))
        }
        val temperatures = root.obj("temperatures").orEmpty().mapNotNull { (name, value) ->
            val celsius = when (value) {
                is JsonObject -> value.number("temperature") ?: value.number("value")
                else -> value.numericValue()
            } ?: return@mapNotNull null
            TemperatureReading(humanize(name), celsius)
        }.sortedBy(TemperatureReading::name)

        return FrigatePerformanceSummary(
            version = service?.string("version")?.takeIf(String::isNotBlank) ?: fallbackVersion,
            uptimeSeconds = service?.number("uptime"),
            cameraFps = root.number("camera_fps"),
            processFps = root.number("process_fps"),
            detectionFps = root.number("detection_fps"),
            skippedFps = root.number("skipped_fps"),
            systemCpuPercent = fullSystem?.number("cpu"),
            frigateCpuPercent = cpuProcesses.mapNotNull { it.number("cpu") }.takeIf(List<Double>::isNotEmpty)?.sum(),
            frigateMemoryPercent = cpuProcesses.mapNotNull { it.number("mem") ?: it.number("memory") }
                .takeIf(List<Double>::isNotEmpty)?.sum(),
            detectors = detectors,
            accelerators = accelerators,
            cameras = cameras,
            temperatures = temperatures,
        )
    }

    fun parseBirdseyeStatus(
        configJson: String,
        metadata: Map<String, StreamMetadata>,
    ): BirdseyeStatus {
        val root = json.parseToJsonElement(configJson).jsonObject
        val birdseye = root.obj("birdseye")
        val enabled = birdseye?.bool("enabled") == true
        val restreamConfigured = birdseye?.bool("restream") == true
        val candidate = metadata.entries.firstOrNull {
            it.key.equals("birdseye", ignoreCase = true)
        }
        return BirdseyeStatus(
            enabled = enabled,
            restreamConfigured = restreamConfigured,
            streamAvailable = candidate?.value?.available == true,
            streamName = candidate?.key,
        )
    }

    private fun collectEvidence(
        element: JsonElement,
        relevantParent: Boolean,
        destination: MutableList<String>,
    ) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                val relevant = relevantParent || key.lowercase() in EVIDENCE_KEYS
                collectEvidence(value, relevant, destination)
            }
            is JsonArray -> element.forEach { collectEvidence(it, relevantParent, destination) }
            is JsonNull -> Unit
            else -> if (relevantParent) {
                element.jsonPrimitive.contentOrNull?.let(destination::add)
            }
        }
    }

    private fun detectVideoCodec(value: String): VideoCodec = when {
        HEVC.containsMatchIn(value) -> VideoCodec.HEVC
        AVC.containsMatchIn(value) -> VideoCodec.AVC
        else -> VideoCodec.UNKNOWN
    }

    private fun detectAudioCodec(value: String, evidence: List<String>): AudioCodec = when {
        OPUS.containsMatchIn(value) -> AudioCodec.OPUS
        AAC.containsMatchIn(value) -> AudioCodec.AAC
        PCMA.containsMatchIn(value) -> AudioCodec.PCMA
        PCMU.containsMatchIn(value) -> AudioCodec.PCMU
        evidence.any { it.contains("audio", ignoreCase = true) } -> AudioCodec.UNKNOWN
        else -> AudioCodec.NONE
    }

    private fun humanize(name: String): String = name.replace('_', ' ')

    private fun parseAccelerators(source: JsonObject?, kind: String): List<AcceleratorPerformance> =
        source.orEmpty().mapNotNull { (name, value) ->
            val item = value as? JsonObject ?: return@mapNotNull null
            AcceleratorPerformance(
                name = humanize(name),
                kind = kind,
                usagePercent = item.number("gpu")
                    ?: item.number("npu")
                    ?: item.number("usage")
                    ?: item.number("utilization")
                    ?: item.number("load"),
                memoryPercent = item.number("mem")
                    ?: item.number("memory")
                    ?: item.number("memory_usage"),
            )
        }.sortedBy(AcceleratorPerformance::name)

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = get(key)?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.int(key: String): Int? = get(key)?.jsonPrimitive?.intOrNull
    private fun JsonObject.double(key: String): Double? = get(key)?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.number(key: String): Double? = get(key)?.numericValue()
    private fun JsonElement.numericValue(): Double? = runCatching {
        jsonPrimitive.doubleOrNull ?: NUMBER.find(jsonPrimitive.contentOrNull.orEmpty())
            ?.value?.toDoubleOrNull()
    }.getOrNull()
    private fun JsonObject?.stringList(key: String): List<String> =
        (this?.get(key) as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    private fun JsonObject?.intList(key: String): List<Int> =
        (this?.get(key) as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty()

    private fun JsonObject.reviewPayload(): JsonObject? = when (val payload = get("payload")) {
        is JsonObject -> payload
        is JsonPrimitive -> payload.contentOrNull?.let { encoded ->
            if (encoded.length > MAX_REALTIME_REVIEW_JSON_CHARACTERS) {
                null
            } else {
                runCatching { json.parseToJsonElement(encoded) as? JsonObject }.getOrNull()
            }
        }
        else -> null
    }

    private fun JsonObject.toRealtimeReviewItem(): RealtimeReviewItem? {
        val id = string("id")?.takeIf { it.isBoundedRealtimeIdentifier() } ?: return null
        val camera = string("camera")?.takeIf { it.isBoundedRealtimeIdentifier() } ?: return null
        val startTime = double("start_time")?.takeIf(Double::isFinite) ?: return null
        val endTime = double("end_time")?.takeIf(Double::isFinite)
        val rawSeverity = string("severity")?.trim()?.lowercase()
        if (rawSeverity != null && !rawSeverity.isBoundedRealtimeText(MAX_REALTIME_RAW_VALUE_CHARACTERS)) {
            return null
        }
        val thumbnailPath = string("thumb_path")
        if (
            thumbnailPath != null &&
            !thumbnailPath.isBoundedRealtimeText(MAX_REALTIME_THUMBNAIL_CHARACTERS)
        ) {
            return null
        }
        val data = obj("data")
        val budget = RealtimePayloadBudget()
        val objects = data.boundedRealtimeStringSet("objects", budget) ?: return null
        val zones = data.boundedRealtimeStringSet("zones", budget) ?: return null
        val audio = data.boundedRealtimeStringSet("audio", budget) ?: return null
        val detectionIds = data.boundedRealtimeStringSet("detections", budget) ?: return null
        val subLabels = data.boundedRealtimeStringSet("sub_labels", budget) ?: return null
        val significantMotionAreas = data.boundedRealtimeIntSet("significant_motion_areas") ?: return null
        val metadata = data?.obj("metadata")
        if (metadata != null && !metadata.isBoundedRealtimeSummary(budget)) return null
        return RealtimeReviewItem(
            id = id,
            camera = camera,
            startTime = startTime,
            endTime = endTime,
            severity = rawSeverity.toReviewSeverity(),
            rawSeverity = rawSeverity,
            thumbnailPath = thumbnailPath,
            objects = objects,
            zones = zones,
            audio = audio,
            detectionIds = detectionIds,
            subLabels = subLabels,
            significantMotionAreas = significantMotionAreas,
            summary = metadata.toReviewSummary(),
            hasBeenReviewed = bool("has_been_reviewed"),
        )
    }

    private class RealtimePayloadBudget {
        private var characters: Int = 0

        fun consume(value: String): Boolean {
            if (characters > MAX_REALTIME_AGGREGATE_CHARACTERS - value.length) return false
            characters += value.length
            return true
        }
    }

    private fun JsonObject?.boundedRealtimeStringSet(
        key: String,
        budget: RealtimePayloadBudget,
    ): Set<String>? {
        val element = this?.get(key) ?: return emptySet()
        val array = element as? JsonArray ?: return null
        if (array.size > MAX_REALTIME_ARRAY_ITEMS) return null
        val result = linkedSetOf<String>()
        for (item in array) {
            val value = (item as? JsonPrimitive)?.contentOrNull ?: return null
            if (!value.isBoundedRealtimeText(MAX_REALTIME_ARRAY_ITEM_CHARACTERS)) return null
            if (!budget.consume(value)) return null
            result += value
        }
        return result
    }

    private fun JsonObject?.boundedRealtimeIntSet(key: String): Set<Int>? {
        val element = this?.get(key) ?: return emptySet()
        val array = element as? JsonArray ?: return null
        if (array.size > MAX_REALTIME_ARRAY_ITEMS) return null
        val result = linkedSetOf<Int>()
        for (item in array) {
            result += (item as? JsonPrimitive)?.intOrNull ?: return null
        }
        return result
    }

    private fun JsonObject.isBoundedRealtimeSummary(budget: RealtimePayloadBudget): Boolean {
        val boundedFields = mapOf(
            "title" to MAX_REALTIME_TITLE_CHARACTERS,
            "shortSummary" to MAX_REALTIME_SUMMARY_CHARACTERS,
            "short_summary" to MAX_REALTIME_SUMMARY_CHARACTERS,
            "scene" to MAX_REALTIME_SCENE_CHARACTERS,
        )
        for ((key, maximum) in boundedFields) {
            val element = get(key) ?: continue
            val value = (element as? JsonPrimitive)?.contentOrNull ?: return false
            if (!value.isBoundedRealtimeText(maximum) || !budget.consume(value)) return false
        }
        return boundedRealtimeStringSet("other_concerns", budget) != null
    }

    private fun String.isBoundedRealtimeIdentifier(): Boolean =
        isNotBlank() && isBoundedRealtimeText(MAX_REALTIME_IDENTIFIER_CHARACTERS)

    private fun String.isBoundedRealtimeText(maximumCharacters: Int): Boolean =
        length <= maximumCharacters && none { character ->
            character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()
        }

    private fun JsonObject?.toReviewSummary(): ReviewSummaryMetadata? = this?.let {
        ReviewSummaryMetadata(
            title = it.string("title")?.takeIf(String::isNotBlank),
            shortSummary = (it.string("shortSummary") ?: it.string("short_summary"))
                ?.takeIf(String::isNotBlank),
            scene = it.string("scene")?.takeIf(String::isNotBlank),
            potentialThreatLevel = it.int("potential_threat_level"),
            otherConcerns = it.stringList("other_concerns"),
        )
    }?.takeIf { summary ->
        summary.title != null || summary.shortSummary != null || summary.scene != null ||
            summary.potentialThreatLevel != null || summary.otherConcerns.isNotEmpty()
    }

    private fun String?.toReviewSeverity(): ReviewSeverity = when (this) {
        "alert" -> ReviewSeverity.ALERT
        "detection" -> ReviewSeverity.DETECTION
        "significant_motion" -> ReviewSeverity.SIGNIFICANT_MOTION
        else -> ReviewSeverity.UNKNOWN
    }

    private fun String?.toReviewLifecycle(): ReviewLifecycle = when (this) {
        "new" -> ReviewLifecycle.NEW
        "update" -> ReviewLifecycle.UPDATE
        "end" -> ReviewLifecycle.END
        "genai" -> ReviewLifecycle.GENAI
        else -> ReviewLifecycle.UNKNOWN
    }

    companion object {
        private const val MAX_REALTIME_REVIEW_JSON_CHARACTERS = 1_048_576
        private const val MAX_REALTIME_IDENTIFIER_CHARACTERS = 256
        private const val MAX_REALTIME_RAW_VALUE_CHARACTERS = 128
        private const val MAX_REALTIME_THUMBNAIL_CHARACTERS = 2_048
        private const val MAX_REALTIME_ARRAY_ITEMS = 256
        private const val MAX_REALTIME_ARRAY_ITEM_CHARACTERS = 512
        private const val MAX_REALTIME_AGGREGATE_CHARACTERS = 262_144
        private const val MAX_REALTIME_TITLE_CHARACTERS = 160
        private const val MAX_REALTIME_SUMMARY_CHARACTERS = 1_000
        private const val MAX_REALTIME_SCENE_CHARACTERS = 500

        private val EVIDENCE_KEYS = setOf(
            "medias", "media", "sdp", "codec", "codec_name", "video", "audio", "resolution",
        )
        private val AVC = Regex("(?i)(H\\.?264|AVC1?|video/avc)")
        private val HEVC = Regex("(?i)(H\\.?265|HEVC|HVC1|video/hevc)")
        private val OPUS = Regex("(?i)(OPUS|audio/opus)")
        private val AAC = Regex("(?i)(MPEG4-GENERIC|MP4A|AAC|audio/mp4a-latm)")
        private val PCMA = Regex("(?i)(PCMA|G711A|G\\.711 A)")
        private val PCMU = Regex("(?i)(PCMU|G711U|G\\.711 (mu|μ))")
        private val RESOLUTION = Regex("(?i)(\\d{3,5})\\s*[x×]\\s*(\\d{3,5})")
        private val NUMBER = Regex("-?\\d+(?:\\.\\d+)?")
    }
}
