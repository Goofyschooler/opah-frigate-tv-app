package app.opah.tv.ui

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
internal fun CameraDiagnosticDataScreen(
    state: Phase0UiState,
    onRefresh: () -> Unit,
    onLoadPlaybackChoices: () -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    val snapshot = state.snapshot
    val device = state.device
    LaunchedEffect(state.activeProfile, snapshot?.cameras, device) {
        if (snapshot != null && device != null) onLoadPlaybackChoices()
    }
    FlatSettingsDetailColumn(
        title = "Diagnostic data",
        subtitle = "Technical camera and playback details",
        onBack = onBack,
    ) {
        item(key = "camera-diagnostic-data-refresh") {
            FlatSettingsRow(
                focusKey = "settings:camera-diagnostic-data:refresh",
                title = if (state.loading) "Refreshing…" else "Refresh diagnostic data",
                value = null,
                enabled = !state.loading,
                focusable = true,
                externalFocusRequester = initialFocusRequester,
                onClick = onRefresh,
            )
        }
        item(key = "camera-diagnostic-data-explanation") {
            SettingsSection(
                "For troubleshooting",
                "Detailed information is loaded only while this page is open",
                isFocusable = true,
            ) {
                Text(
                    "Only permitted cameras are shown. Stream URLs, credentials, and discovery evidence stay hidden",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (snapshot == null) {
            item(key = "camera-diagnostic-data-loading") {
                SettingsSection("Cameras", isFocusable = true) {
                    Text(
                        "Camera information is still loading",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (snapshot.cameras.isEmpty()) {
            item(key = "camera-diagnostic-data-empty") {
                SettingsSection("Cameras", isFocusable = true) {
                    Text(
                        "No permitted cameras are available",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            snapshot.cameras.forEach { camera ->
                val report = cameraTechnicalReport(
                    camera = camera,
                    metadataByStream = snapshot.streamMetadata,
                    device = device,
                    settings = state.settings,
                    savedPlaybackChoice = state.playbackCompatibilityChoices[camera.name],
                )
                item(key = "camera-diagnostic-data:${camera.name}:overview") {
                    CameraTechnicalOverviewSection(camera.displayName, report)
                }
                itemsIndexed(
                    items = report.streams,
                    key = { index, _ -> "camera-diagnostic-data:${camera.name}:stream:$index" },
                ) { index, stream ->
                    CameraStreamTechnicalSection(
                        cameraName = camera.displayName,
                        stream = stream,
                        position = index + 1,
                        total = report.streams.size,
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraTechnicalOverviewSection(
    cameraName: String,
    report: CameraTechnicalReport,
) {
    val streamChoiceSummary = if (report.configuredStreamCount == 1) {
        "1 live stream choice found"
    } else {
        "${report.configuredStreamCount} live stream choices found"
    }
    SettingsSection(
        cameraName,
        streamChoiceSummary,
        isFocusable = true,
    ) {
        ReadOnlyValue("Camera ID", report.cameraId)
        ReadOnlyValue("Live choices", report.configuredStreamCount.toString())
        ReadOnlyValue("Default stream", report.defaultStreamPreference)
        ReadOnlyValue("Connection", report.defaultConnection)
        ReadOnlyValue("Saved playback", report.savedPlaybackChoice)
        if (report.streams.isEmpty()) {
            Text(
                "No live stream choices were discovered for this camera",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CameraStreamTechnicalSection(
    cameraName: String,
    stream: CameraStreamTechnicalReport,
    position: Int,
    total: Int,
) {
    SettingsSection(
        "$cameraName • ${stream.optionLabel}",
        "Stream $position of $total",
        isFocusable = true,
    ) {
        ReadOnlyValue("Stream ID", stream.streamId)
        ReadOnlyValue("Frigate status", stream.frigateStatus)
        ReadOnlyValue("Video", stream.videoFormat)
        ReadOnlyValue("Resolution", stream.resolution)
        ReadOnlyValue("TV video support", stream.videoDecoderSupport)
        ReadOnlyValue("Video decoders", stream.videoDecoderNames)
        ReadOnlyValue("Audio", stream.audioFormat)
        ReadOnlyValue("TV audio support", stream.audioDecoderSupport)
        ReadOnlyValue("Audio decoders", stream.audioDecoderNames)
    }
}
