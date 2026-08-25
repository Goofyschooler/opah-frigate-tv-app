package app.opah.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.data.model.ExportIncident
import app.opah.tv.data.model.FrigateFeature
import app.opah.tv.data.model.RecordingExport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class ClipsPage(val label: String) {
    CLIPS("Clips"),
    INCIDENTS("Incidents"),
}

@Composable
internal fun ClipsScreen(
    state: Phase0UiState,
    initialPageName: String? = null,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onLoad: () -> Unit,
    onPlay: (RecordingExport) -> Unit,
    onRename: (RecordingExport, String) -> Unit,
    onAssignIncident: (RecordingExport, String?) -> Unit,
    onShare: (RecordingExport) -> Unit,
    onDelete: (RecordingExport) -> Unit,
    onCreateIncident: (String, String?) -> Unit,
    onUpdateIncident: (ExportIncident, String, String?) -> Unit,
    onDeleteIncident: (ExportIncident, Boolean) -> Unit,
    cachedBitmap: (RecordingExport) -> Bitmap?,
    refreshBitmap: suspend (RecordingExport, Int) -> Bitmap?,
) {
    val snapshot = state.snapshot ?: return
    val exports = state.exports
    val incidentsAvailable = snapshot.capabilities.supports(FrigateFeature.EXPORT_CASES)
    val mutationsAvailable = snapshot.capabilities.supports(FrigateFeature.INCIDENT_MUTATION)
    val initialPage = initialPageName
        ?.let { runCatching { ClipsPage.valueOf(it) }.getOrNull() }
        ?.takeIf { it != ClipsPage.INCIDENTS || incidentsAvailable }
        ?: ClipsPage.CLIPS
    var page by rememberSaveable(initialPage) { mutableStateOf(initialPage) }
    val clipPreviewState = rememberSaveable(saver = ClipPreviewState.Saver) { ClipPreviewState() }
    var selectedIncidentId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionClip by remember { mutableStateOf<RecordingExport?>(null) }
    var renameClip by remember { mutableStateOf<RecordingExport?>(null) }
    var incidentClip by remember { mutableStateOf<RecordingExport?>(null) }
    var deleteClip by remember { mutableStateOf<RecordingExport?>(null) }
    var editIncident by remember { mutableStateOf<ExportIncident?>(null) }
    var createIncidentVisible by rememberSaveable { mutableStateOf(false) }
    var deleteIncident by remember { mutableStateOf<ExportIncident?>(null) }
    val clipsPageFocusRequester = remember { FocusRequester() }
    val clipPlayFocusRequester = remember { FocusRequester() }
    val firstClipFocusRequester = remember { FocusRequester() }
    val clipPreviewScope = rememberCoroutineScope()
    val clipPreviewUpdate = remember { ClipPreviewUpdate() }

    LaunchedEffect(state.activeProfile, exports.loadedOnce) {
        if (!exports.loadedOnce) onLoad()
    }
    LaunchedEffect(exports.items) {
        if (clipPreviewState.clipId.value !in exports.items.map(RecordingExport::id)) {
            clipPreviewState.clipId.value = exports.items.firstOrNull()?.id
            clipPreviewUpdate.pendingClipId = clipPreviewState.clipId.value
        }
    }
    LaunchedEffect(exports.incidents) {
        if (selectedIncidentId !in exports.incidents.map(ExportIncident::id)) {
            selectedIncidentId = exports.incidents.firstOrNull()?.id
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(
            start = OpahDesignTokens.ScreenHorizontalMargin,
            top = OpahDesignTokens.ScreenVerticalMargin,
            end = OpahDesignTokens.ScreenHorizontalMargin,
            bottom = OpahDesignTokens.ScreenVerticalMargin,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column {
                Text("Clips", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Video you chose to keep", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            if (exports.loading || exports.operationBusy) {
                Text("Updating…", color = MaterialTheme.colorScheme.secondary)
            }
        }
        if (incidentsAvailable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClipsPage.entries.forEach { option ->
                    SegmentedTab(
                        focusKey = "clips:page:${option.name}",
                        label = option.label,
                        onClick = { page = option },
                        selected = page == option,
                        restoreFocusKey = restoreFocusKey,
                        onFocusRestored = onFocusRestored,
                        externalFocusRequester = clipsPageFocusRequester.takeIf { page == option },
                        modifier = Modifier.focusProperties { down = clipPlayFocusRequester },
                    )
                }
            }
        }
        exports.operationMessage?.let { ScreenMessage(it, isError = false) }
        exports.errorMessage?.let { ScreenMessage(it, isError = true) }

        when (page) {
            ClipsPage.CLIPS -> {
                if (exports.items.isNotEmpty()) {
                    ClipHeroPreview(
                        items = exports.items,
                        incidents = exports.incidents,
                        state = state,
                        previewState = clipPreviewState,
                        previewUpdate = clipPreviewUpdate,
                        canMutate = mutationsAvailable,
                        deletingItemId = exports.deletingItemId,
                        onPlay = onPlay,
                        onMore = { actionClip = it },
                        cachedBitmap = cachedBitmap,
                        refreshBitmap = refreshBitmap,
                        playFocusRequester = clipPlayFocusRequester,
                        pageFocusRequester = clipsPageFocusRequester.takeIf { incidentsAvailable },
                        firstClipFocusRequester = firstClipFocusRequester,
                    )
                }
                when {
                    exports.loading && exports.items.isEmpty() -> ScreenMessage("Loading clips…", isError = false)
                    exports.loadedOnce && exports.items.isEmpty() -> ScreenMessage("There are no clips yet", isError = false)
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(CLIP_GRID_COLUMNS),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(bottom = CLIP_GRID_BOTTOM_PADDING),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(exports.items, key = { _, export -> export.id }) { index, export ->
                            MediaTile(
                                focusKey = "clips:item:${export.id}",
                                accessibilityLabel = "${clipTitle(export)}, ${clipStateLabel(export)}",
                                onClick = { if (!export.inProgress) onPlay(export) },
                                onLongClick = { if (mutationsAvailable) actionClip = export },
                                selected = export.id == clipPreviewState.clipId.value,
                                restoreFocusKey = restoreFocusKey,
                                onFocusRestored = onFocusRestored,
                                onFocused = {
                                    clipPreviewUpdate.pendingClipId = export.id
                                    clipPreviewUpdate.job?.cancel()
                                    if (clipPreviewState.clipId.value != export.id) {
                                        clipPreviewUpdate.job = clipPreviewScope.launch {
                                            delay(CLIP_PREVIEW_SETTLE_MILLIS)
                                            clipPreviewState.clipId.value = export.id
                                        }
                                    }
                                },
                                externalFocusRequester = firstClipFocusRequester.takeIf { index == 0 },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusProperties {
                                        if (index < 4) up = clipPlayFocusRequester
                                    },
                            ) {
                                Column {
                                    SavedRecordingThumbnail(
                                        export = export,
                                        cachedBitmap = { cachedBitmap(export) },
                                        refreshBitmap = { refreshBitmap(export, 220) },
                                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                                    )
                                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                        Text(
                                            clipTitle(export),
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            clipStateLabel(export),
                                            color = if (export.inProgress) {
                                                MaterialTheme.colorScheme.secondary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ClipsPage.INCIDENTS -> IncidentsContent(
                incidents = exports.incidents,
                clips = exports.items,
                selectedIncidentId = selectedIncidentId,
                canMutate = mutationsAvailable,
                errorMessage = exports.incidentsErrorMessage,
                onSelected = { selectedIncidentId = it },
                onPlay = onPlay,
                onCreate = { createIncidentVisible = true },
                onEdit = { editIncident = it },
                onDelete = { deleteIncident = it },
            )
        }
    }

    actionClip?.let { export ->
        ClipActionsDialog(
            export = export,
            canUseIncidents = incidentsAvailable && mutationsAvailable,
            onDismiss = { actionClip = null },
            onRename = {
                actionClip = null
                renameClip = export
            },
            onIncident = {
                actionClip = null
                incidentClip = export
            },
            onShare = {
                actionClip = null
                onShare(export)
            },
            onDelete = {
                actionClip = null
                deleteClip = export
            },
        )
    }
    renameClip?.let { export ->
        NameDialog(
            title = "Rename clip",
            initialName = clipTitle(export),
            initialDescription = null,
            includeDescription = false,
            onDismiss = { renameClip = null },
            onSave = { name, _ ->
                renameClip = null
                onRename(export, name)
            },
        )
    }
    incidentClip?.let { export ->
        IncidentPickerDialog(
            incidents = exports.incidents,
            selectedIncidentId = export.incidentId,
            onDismiss = { incidentClip = null },
            onSelected = { incidentId ->
                incidentClip = null
                onAssignIncident(export, incidentId)
            },
        )
    }
    deleteClip?.let { export ->
        ConfirmClipDeleteDialog(
            export = export,
            onDismiss = { deleteClip = null },
            onConfirm = {
                deleteClip = null
                onDelete(export)
            },
        )
    }
    if (createIncidentVisible) {
        NameDialog(
            title = "Create Incident",
            initialName = "",
            initialDescription = "",
            includeDescription = true,
            onDismiss = { createIncidentVisible = false },
            onSave = { name, description ->
                createIncidentVisible = false
                onCreateIncident(name, description)
            },
        )
    }
    editIncident?.let { incident ->
        NameDialog(
            title = "Edit Incident",
            initialName = incident.name,
            initialDescription = incident.description.orEmpty(),
            includeDescription = true,
            onDismiss = { editIncident = null },
            onSave = { name, description ->
                editIncident = null
                onUpdateIncident(incident, name, description)
            },
        )
    }
    deleteIncident?.let { incident ->
        IncidentDeleteDialog(
            incident = incident,
            onDismiss = { deleteIncident = null },
            onConfirm = { deleteClips ->
                deleteIncident = null
                onDeleteIncident(incident, deleteClips)
            },
        )
    }
}

@Composable
private fun ClipHeroPreview(
    items: List<RecordingExport>,
    incidents: List<ExportIncident>,
    state: Phase0UiState,
    previewState: ClipPreviewState,
    previewUpdate: ClipPreviewUpdate,
    canMutate: Boolean,
    deletingItemId: String?,
    onPlay: (RecordingExport) -> Unit,
    onMore: (RecordingExport) -> Unit,
    cachedBitmap: (RecordingExport) -> Bitmap?,
    refreshBitmap: suspend (RecordingExport, Int) -> Bitmap?,
    playFocusRequester: FocusRequester,
    pageFocusRequester: FocusRequester?,
    firstClipFocusRequester: FocusRequester,
) {
    val selectedClipId by previewState.clipId
    val selected = items.firstOrNull { it.id == selectedClipId } ?: items.first()
    ClipHero(
        export = selected,
        cameraLabel = clipCameraLabel(selected, state),
        incident = incidents.firstOrNull { it.id == selected.incidentId },
        canMutate = canMutate,
        deleting = deletingItemId == selected.id,
        onPlay = { onPlay(selected) },
        onMore = { onMore(selected) },
        cachedBitmap = { cachedBitmap(selected) },
        refreshBitmap = { refreshBitmap(selected, 540) },
        playFocusRequester = playFocusRequester,
        pageFocusRequester = pageFocusRequester,
        firstClipFocusRequester = firstClipFocusRequester,
        onHeroFocused = {
            previewUpdate.job?.cancel()
            previewUpdate.pendingClipId?.let { pendingId ->
                if (items.any { it.id == pendingId }) previewState.clipId.value = pendingId
            }
        },
    )
}

@Composable
private fun ClipHero(
    export: RecordingExport,
    cameraLabel: String,
    incident: ExportIncident?,
    canMutate: Boolean,
    deleting: Boolean,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    cachedBitmap: () -> Bitmap?,
    refreshBitmap: suspend () -> Bitmap?,
    playFocusRequester: FocusRequester,
    pageFocusRequester: FocusRequester?,
    firstClipFocusRequester: FocusRequester,
    onHeroFocused: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(CLIP_HERO_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SavedRecordingThumbnail(
            export = export,
            cachedBitmap = cachedBitmap,
            refreshBitmap = refreshBitmap,
            modifier = Modifier.width(CLIP_HERO_WIDTH).aspectRatio(16f / 9f),
        )
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(clipTitle(export), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(cameraLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date((export.createdAt * 1_000).toLong())),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                incident?.let { "Incident: ${it.name}" } ?: clipStateLabel(export),
                color = if (export.inProgress) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryAction(
                    focusKey = "clips:play",
                    label = if (export.inProgress) "Still saving" else "Play",
                    onClick = onPlay,
                    enabled = !export.inProgress && !deleting,
                    externalFocusRequester = playFocusRequester,
                    onFocused = onHeroFocused,
                    modifier = Modifier.focusProperties {
                        pageFocusRequester?.let { up = it }
                        down = firstClipFocusRequester
                    },
                )
                if (canMutate) {
                    SecondaryAction(
                        focusKey = "clips:more",
                        label = "More",
                        onClick = onMore,
                        enabled = !export.inProgress && !deleting,
                        onFocused = onHeroFocused,
                        modifier = Modifier.focusProperties {
                            pageFocusRequester?.let { up = it }
                            down = firstClipFocusRequester
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun IncidentsContent(
    incidents: List<ExportIncident>,
    clips: List<RecordingExport>,
    selectedIncidentId: String?,
    canMutate: Boolean,
    errorMessage: String?,
    onSelected: (String) -> Unit,
    onPlay: (RecordingExport) -> Unit,
    onCreate: () -> Unit,
    onEdit: (ExportIncident) -> Unit,
    onDelete: (ExportIncident) -> Unit,
) {
    val selected = incidents.firstOrNull { it.id == selectedIncidentId }
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        LazyColumn(
            modifier = Modifier.width(360.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canMutate) {
                item {
                    PrimaryAction("incidents:create", "Create Incident", onCreate)
                }
            }
            items(incidents, key = ExportIncident::id) { incident ->
                val count = clips.count { it.incidentId == incident.id }
                MediaTile(
                    focusKey = "incidents:item:${incident.id}",
                    accessibilityLabel = "${incident.name}, $count clips",
                    onClick = { onSelected(incident.id) },
                    onFocused = { onSelected(incident.id) },
                    selected = incident.id == selectedIncidentId,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(incident.name, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("$count clips", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            errorMessage?.let { ScreenMessage(it, isError = true) }
            when {
                selected == null && incidents.isEmpty() -> ScreenMessage("There are no Incidents yet", isError = false)
                selected != null -> {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(selected.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                selected.description ?: "Clips kept together for the same moment",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (canMutate) {
                            SecondaryAction("incidents:edit", "Edit", { onEdit(selected) })
                            Spacer(Modifier.width(8.dp))
                            SecondaryAction("incidents:delete", "Delete", { onDelete(selected) })
                        }
                    }
                    val incidentClips = clips.filter { it.incidentId == selected.id }
                    if (incidentClips.isEmpty()) {
                        ScreenMessage("This Incident has no clips yet", isError = false)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(incidentClips, key = RecordingExport::id) { clip ->
                                MediaTile(
                                    focusKey = "incident:clip:${clip.id}",
                                    accessibilityLabel = clipTitle(clip),
                                    onClick = { onPlay(clip) },
                                    enabled = !clip.inProgress,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(clipTitle(clip), fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(clipStateLabel(clip), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipActionsDialog(
    export: RecordingExport,
    canUseIncidents: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onIncident: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val firstActionFocusRequester = remember(export.id) { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.widthIn(min = 440.dp, max = 620.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(clipTitle(export), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(
                onClick = onRename,
                modifier = Modifier.fillMaxWidth().focusRequester(firstActionFocusRequester),
            ) { Text("Rename") }
            if (canUseIncidents) {
                Button(onClick = onIncident, modifier = Modifier.fillMaxWidth()) {
                    Text(if (export.incidentId == null) "Add to Incident" else "Change Incident")
                }
            }
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text("Share") }
            Button(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete") }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
    LaunchedEffect(export.id) {
        repeat(3) {
            withFrameNanos { }
            if (firstActionFocusRequester.requestFocus()) return@LaunchedEffect
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialName: String,
    initialDescription: String?,
    includeDescription: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit,
) {
    var name by rememberSaveable(title, initialName) { mutableStateOf(initialName) }
    var description by rememberSaveable(title, initialDescription) { mutableStateOf(initialDescription.orEmpty()) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.widthIn(min = 520.dp, max = 680.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ProductionTvInput(
                label = "Name",
                value = name,
                onValueChange = { name = it.take(100) },
                placeholder = "For example: Package arrival",
                enabled = true,
                requestInitialFocus = true,
            )
            if (includeDescription) {
                ProductionTvInput(
                    label = "Description",
                    value = description,
                    onValueChange = { description = it.take(1_000) },
                    placeholder = "Optional",
                    enabled = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(name, description.takeIf(String::isNotBlank)) }, enabled = name.isNotBlank()) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun IncidentPickerDialog(
    incidents: List<ExportIncident>,
    selectedIncidentId: String?,
    onDismiss: () -> Unit,
    onSelected: (String?) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.widthIn(min = 460.dp, max = 640.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Choose Incident", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item {
                Button(onClick = { onSelected(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selectedIncidentId == null) "No Incident ✓" else "No Incident")
                }
            }
            items(incidents, key = ExportIncident::id) { incident ->
                Button(onClick = { onSelected(incident.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selectedIncidentId == incident.id) "${incident.name} ✓" else incident.name)
                }
            }
        }
    }
}

@Composable
private fun ConfirmClipDeleteDialog(
    export: RecordingExport,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.widthIn(min = 440.dp, max = 620.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Delete clip?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${clipTitle(export)} will be permanently deleted from Frigate")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onDismiss) { Text("Keep clip") }
                Button(onClick = onConfirm) { Text("Delete clip") }
            }
        }
    }
}

@Composable
private fun IncidentDeleteDialog(
    incident: ExportIncident,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.widthIn(min = 480.dp, max = 680.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Delete ${incident.name}?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Choose whether the clips inside should stay in Frigate")
            Button(onClick = { onConfirm(false) }, modifier = Modifier.fillMaxWidth()) { Text("Delete Incident, keep clips") }
            Button(onClick = { onConfirm(true) }, modifier = Modifier.fillMaxWidth()) { Text("Delete Incident and clips") }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

private fun clipTitle(export: RecordingExport): String = export.name.replace('_', ' ')

private fun clipStateLabel(export: RecordingExport): String = if (export.inProgress) "Saving…" else "Ready"

private fun clipCameraLabel(export: RecordingExport, state: Phase0UiState): String =
    state.snapshot?.authorizedCameraNames?.get(export.camera)
        ?: state.snapshot?.cameras?.firstOrNull { it.name == export.camera }?.displayName
        ?: export.camera.replace('_', ' ')

private const val CLIP_PREVIEW_SETTLE_MILLIS = 300L
private const val CLIP_GRID_COLUMNS = 5
private val CLIP_GRID_BOTTOM_PADDING = 32.dp
private val CLIP_HERO_HEIGHT = 170.dp
private val CLIP_HERO_WIDTH = 300.dp

private class ClipPreviewUpdate {
    var pendingClipId: String? = null
    var job: Job? = null
}

private class ClipPreviewState(initialClipId: String? = null) {
    val clipId = mutableStateOf(initialClipId)

    companion object {
        val Saver = Saver<ClipPreviewState, String>(
            save = { state -> state.clipId.value.orEmpty() },
            restore = { saved -> ClipPreviewState(saved.takeIf(String::isNotEmpty)) },
        )
    }
}
