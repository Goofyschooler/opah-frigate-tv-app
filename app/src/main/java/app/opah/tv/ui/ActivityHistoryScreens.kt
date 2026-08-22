package app.opah.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.FrigateCapabilityAvailability
import app.opah.tv.data.model.FrigateFeature
import app.opah.tv.data.model.SearchEvent
import app.opah.tv.data.model.RecordingHourSummary
import app.opah.tv.data.model.isSafeLiteralLicensePlateFilter
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class ActivityPage(val label: String) {
    RECENT("Recent"),
    HISTORY("History"),
    SEARCH("Search"),
}

@Composable
internal fun ActivityNavigation(
    selected: ActivityPage,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onSelected: (ActivityPage) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActivityPage.entries.forEach { page ->
            FocusCard(
                focusKey = "activity:page:${page.name}",
                restoreFocusKey = restoreFocusKey,
                onFocusRestored = onFocusRestored,
                onClick = { onSelected(page) },
                selected = page == selected,
                accessibilityLabel = "${page.label} activity",
            ) {
                Text(
                    page.label,
                    color = if (page == selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
internal fun ActivityHistoryScreen(
    state: Phase0UiState,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onPage: (ActivityPage) -> Unit,
    onLoad: (String?, Double?) -> Unit,
    onMoveHour: (Int) -> Unit,
    onPlay: (HistorySlot, String) -> Unit,
) {
    val cameras = state.snapshot?.cameras.orEmpty()
    val history = state.history
    val now = System.currentTimeMillis() / 1000.0
    var cameraDialogVisible by rememberSaveable { mutableStateOf(false) }
    var dayDialogVisible by rememberSaveable { mutableStateOf(false) }
    var hourDialogVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(history.loadedOnce, cameras) {
        if (!history.loadedOnce && !history.loading && cameras.isNotEmpty()) onLoad(null, null)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(start = 24.dp, top = 18.dp, end = 24.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text("Activity", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Choose a camera, day, and time", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (history.loading) Text("Loading…", color = MaterialTheme.colorScheme.secondary)
            }
            ActivityNavigation(ActivityPage.HISTORY, restoreFocusKey, onFocusRestored, onPage)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { cameraDialogVisible = true }) {
                    Text(historyCameraLabel(history.cameraName, cameras), maxLines = 1)
                }
                Button(
                    onClick = { dayDialogVisible = true },
                    enabled = history.hourSummaries.isNotEmpty() && !history.summaryLoading,
                ) {
                    Text(history.hourStartSeconds?.let(::formatHistoryDay) ?: "Choose day")
                }
                Button(
                    onClick = { hourDialogVisible = true },
                    enabled = history.hourSummaries.isNotEmpty() && !history.summaryLoading,
                ) {
                    Text(history.hourStartSeconds?.let(::formatHistoryTime) ?: "Choose time")
                }
                Button(onClick = { onMoveHour(-1) }) { Text("Earlier") }
                Button(
                    onClick = { onMoveHour(1) },
                    enabled = history.hourStartSeconds?.let { canMoveHistoryForward(it, now) } == true,
                ) { Text("Later") }
                Button(onClick = { onLoad(history.cameraName, hourStart(now)) }) { Text("Now") }
                Spacer(Modifier.weight(1f))
                history.hourStartSeconds?.let { start ->
                    Text(
                        formatHistoryHour(start),
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        history.errorMessage?.let { error ->
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScreenMessage(error, isError = true, modifier = Modifier.weight(1f))
                Button(onClick = { onLoad(history.cameraName, history.hourStartSeconds) }) { Text("Retry") }
            }
        }
        history.savedMessage?.let { message ->
            ScreenMessage(
                message,
                isError = false,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }

        val slots = history.hourStartSeconds?.let { historySlots(it, history.segments, now) }.orEmpty()
        when {
            history.loading && !history.loadedOnce -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading saved video…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            slots.none(HistorySlot::available) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No saved video during this hour", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                slots.forEach { slot ->
                    val focusKey = "history:slot:${slot.startTime.toLong()}"
                    FocusCard(
                        focusKey = focusKey,
                        restoreFocusKey = restoreFocusKey,
                        onFocusRestored = onFocusRestored,
                        onClick = { onPlay(slot, focusKey) },
                        enabled = slot.available,
                        accessibilityLabel = historySlotAccessibilityLabel(slot),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(formatHistoryTime(slot.startTime), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (slot.available) "Watch" else "No video",
                                color = if (slot.available) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = FontWeight.Bold,
                            )
                            if (slot.available) {
                                val motionLevel = slot.motionLevel(history.motion)
                                Text(
                                    motionLevelLabel(motionLevel),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(motionLevelFraction(motionLevel))
                                            .height(5.dp)
                                            .background(MaterialTheme.colorScheme.primary),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (cameraDialogVisible) {
        ActivityCameraDialog(
            cameras = cameras,
            selectedCameraName = history.cameraName,
            onDismiss = { cameraDialogVisible = false },
            onSelected = { camera ->
                val selected = camera ?: return@ActivityCameraDialog
                cameraDialogVisible = false
                onLoad(selected.name, history.hourStartSeconds)
            },
        )
    }
    if (dayDialogVisible) {
        val days = history.hourSummaries.map(RecordingHourSummary::day).distinct().sortedDescending()
        val selectedDay = history.hourStartSeconds?.let(::historyDayKey)
        ActivityChoiceDialog(
            title = "Choose day",
            options = days.map { day -> day to formatHistoryDayKey(day) },
            selectedKey = selectedDay,
            onDismiss = { dayDialogVisible = false },
            onSelected = { day ->
                dayDialogVisible = false
                history.hourSummaries.filter { it.day == day }
                    .maxByOrNull(RecordingHourSummary::hour)
                    ?.let { onLoad(history.cameraName, recordingHourStart(it)) }
            },
        )
    }
    if (hourDialogVisible) {
        val selectedDay = history.hourStartSeconds?.let(::historyDayKey)
        val hours = history.hourSummaries.filter { it.day == selectedDay }
            .sortedBy(RecordingHourSummary::hour)
        ActivityChoiceDialog(
            title = "Choose time",
            options = hours.map { summary ->
                recordingHourStart(summary).toLong().toString() to formatHistoryTime(recordingHourStart(summary))
            },
            selectedKey = history.hourStartSeconds?.toLong()?.toString(),
            onDismiss = { hourDialogVisible = false },
            onSelected = { epoch ->
                hourDialogVisible = false
                epoch.toLongOrNull()?.toDouble()?.let { onLoad(history.cameraName, it) }
            },
        )
    }
}

@Composable
private fun ActivityChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedKey: String?,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 560.dp)
                .heightIn(max = 680.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(options, key = { it.first }) { (key, label) ->
                    FocusCard(
                        focusKey = "activity:choice:$key",
                        restoreFocusKey = "activity:choice:${selectedKey ?: options.firstOrNull()?.first.orEmpty()}",
                        onFocusRestored = {},
                        onClick = { onSelected(key) },
                        selected = key == selectedKey,
                        accessibilityLabel = label,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ActivitySearchScreen(
    state: Phase0UiState,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onPage: (ActivityPage) -> Unit,
    onSearch: (String, ActivitySearchFilters) -> Unit,
    onLoadMore: () -> Unit,
    onPlay: (SearchEvent, String) -> Unit,
    cachedBitmap: (SearchEvent) -> Bitmap?,
    refreshBitmap: suspend (SearchEvent, Int) -> Bitmap?,
) {
    val cameras = state.snapshot?.cameras.orEmpty()
    val search = state.activitySearch
    val capability = state.snapshot?.capabilities?.get(FrigateFeature.SEMANTIC_SEARCH)
    var query by rememberSaveable(search.query) { mutableStateOf(search.query) }
    var filters by remember(search.filters) { mutableStateOf(search.filters) }
    var filtersVisible by rememberSaveable { mutableStateOf(false) }
    val searchButtonFocusRequester = remember { FocusRequester() }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(start = 24.dp, top = 18.dp, end = 24.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text("Activity", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        search.similarToLabel?.let { "Activity similar to ${friendlyActivityName(it)}" }
                            ?: "Describe what you want to find",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (search.searching) Text("Searching…", color = MaterialTheme.colorScheme.secondary)
            }
            ActivityNavigation(ActivityPage.SEARCH, restoreFocusKey, onFocusRestored, onPage)
        }

        if (capability?.available != true) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.widthIn(max = 620.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Search unavailable", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (capability?.availability == FrigateCapabilityAvailability.NOT_CONFIGURED) {
                            "Search is not turned on in your Frigate settings"
                        } else {
                            "This Frigate server does not offer activity search"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return
        }

        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            ProductionTvInput(
                label = "Search",
                value = query,
                onValueChange = { query = it.take(MAX_ACTIVITY_SEARCH_LENGTH) },
                placeholder = "For example: red car",
                enabled = !search.searching,
                imeAction = ImeAction.Done,
                nextFocusRequester = searchButtonFocusRequester,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { filtersVisible = true }, enabled = !search.searching) {
                val count = filters.activeCount()
                Text(if (count == 0) "Filters" else "Filters ($count)")
            }
            Button(
                onClick = { onSearch(query, filters) },
                enabled = query.isNotBlank() && !search.searching,
                modifier = Modifier.focusRequester(searchButtonFocusRequester),
            ) { Text("Search") }
        }
        if (filters.activeCount() > 0) {
            Text(
                filters.summary(cameras),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
        }

        search.errorMessage?.let { error ->
            ScreenMessage(
                error,
                isError = true,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }

        when {
            search.searching && search.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Searching saved activity…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            search.searchedOnce && search.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No saved activity matched your search", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            !search.searchedOnce -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Try a color, object, or short description", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> SearchResultGrid(
                results = search.results,
                cameras = cameras,
                restoreFocusKey = restoreFocusKey,
                onFocusRestored = onFocusRestored,
                onPlay = onPlay,
                cachedBitmap = cachedBitmap,
                refreshBitmap = refreshBitmap,
                hasMore = search.hasMore,
                loadingMore = search.loadingMore,
                onLoadMore = onLoadMore,
            )
        }
    }

    if (filtersVisible) {
        ActivitySearchFiltersDialog(
            filters = filters,
            state = state,
            onDismiss = { filtersVisible = false },
            onApply = { selected ->
                filters = selected
                filtersVisible = false
            },
        )
    }
}

@Composable
private fun SearchResultGrid(
    results: List<SearchEvent>,
    cameras: List<Camera>,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onPlay: (SearchEvent, String) -> Unit,
    cachedBitmap: (SearchEvent) -> Bitmap?,
    refreshBitmap: suspend (SearchEvent, Int) -> Bitmap?,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(results, key = SearchEvent::id) { event ->
            val focusKey = "search:event:${event.id}"
            FocusCard(
                focusKey = focusKey,
                restoreFocusKey = restoreFocusKey,
                onFocusRestored = onFocusRestored,
                onClick = { onPlay(event, focusKey) },
                accessibilityLabel = searchEventAccessibilityLabel(event, cameras),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    SearchEventThumbnail(
                        event = event,
                        cachedBitmap = { cachedBitmap(event) },
                        refreshBitmap = { refreshBitmap(event, 300) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                    )
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            event.subLabel?.takeIf(String::isNotBlank)?.let(::friendlyActivityName)
                                ?: friendlyActivityName(event.label),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            historyCameraLabel(event.camera, cameras),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date((event.startTime * 1000).toLong())),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (hasMore || loadingMore) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button(onClick = onLoadMore, enabled = !loadingMore) {
                        Text(if (loadingMore) "Loading…" else "More results")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivitySearchFiltersDialog(
    filters: ActivitySearchFilters,
    state: Phase0UiState,
    onDismiss: () -> Unit,
    onApply: (ActivitySearchFilters) -> Unit,
) {
    var draft by remember(filters) { mutableStateOf(filters) }
    val cameras = state.snapshot?.cameras.orEmpty()
    val results = state.activitySearch.results
    val labels = (state.review.knownLabels + results.map(SearchEvent::label)).sorted()
    val subLabels = results.mapNotNull(SearchEvent::subLabel).distinct().sorted()
    val zones = (state.review.knownZones + results.flatMap(SearchEvent::zones)).sorted()
    val plates = results
        .mapNotNull(SearchEvent::recognizedLicensePlate)
        .map(String::trim)
        .filter(::isSafeLiteralLicensePlateFilter)
        .distinct()
        .sorted()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 520.dp, max = 680.dp)
                .heightIn(max = 700.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Search filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Press a filter to choose its next option", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActivityFilterButton("Camera", historyCameraLabel(draft.cameraName, cameras)) {
                draft = draft.copy(cameraName = cycleValue(draft.cameraName, cameras.map(Camera::name)))
            }
            if (labels.isNotEmpty()) ActivityFilterButton("Object", friendlyFilter(draft.label)) {
                draft = draft.copy(label = cycleValue(draft.label, labels))
            }
            if (subLabels.isNotEmpty()) ActivityFilterButton("Recognized", friendlyFilter(draft.subLabel)) {
                draft = draft.copy(subLabel = cycleValue(draft.subLabel, subLabels))
            }
            if (zones.isNotEmpty()) ActivityFilterButton("Area", friendlyFilter(draft.zone)) {
                draft = draft.copy(zone = cycleValue(draft.zone, zones))
            }
            if (plates.isNotEmpty()) ActivityFilterButton("License plate", draft.recognizedLicensePlate ?: "All") {
                draft = draft.copy(
                    recognizedLicensePlate = cycleValue(draft.recognizedLicensePlate, plates),
                )
            }
            ActivityFilterButton("Time", draft.timeRange.displayName) {
                val ranges = ActivitySearchTimeRange.entries
                draft = draft.copy(timeRange = ranges[(ranges.indexOf(draft.timeRange) + 1) % ranges.size])
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                Button(onClick = { draft = ActivitySearchFilters() }) { Text("Clear") }
                Button(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onApply(draft) }) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun ActivityFilterButton(label: String, value: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(value, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SearchEventThumbnail(
    event: SearchEvent,
    cachedBitmap: () -> Bitmap?,
    refreshBitmap: suspend () -> Bitmap?,
    modifier: Modifier,
) {
    var bitmap by remember(event.id) { mutableStateOf(cachedBitmap()) }
    var unavailable by remember(event.id) { mutableStateOf(false) }
    LaunchedEffect(event.id) {
        val loaded = refreshBitmap()
        if (loaded == null) unavailable = true else bitmap = loaded
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { current ->
            Image(
                bitmap = remember(current) { current.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Text(
            if (unavailable) "Preview unavailable" else "Loading preview…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ActivityCameraDialog(
    cameras: List<Camera>,
    selectedCameraName: String?,
    includeAll: Boolean = false,
    onDismiss: () -> Unit,
    onSelected: (Camera?) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val initialCameraKey = selectedCameraName
            ?: ALL_CAMERAS_KEY.takeIf { includeAll }
            ?: cameras.firstOrNull()?.name.orEmpty()
        val initialKey = "activity:camera:$initialCameraKey"
        Column(
            modifier = Modifier
                .widthIn(min = 460.dp, max = 620.dp)
                .heightIn(max = 700.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose camera", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (includeAll) {
                    item(key = ALL_CAMERAS_KEY) {
                        ActivityCameraOption(
                            key = ALL_CAMERAS_KEY,
                            label = "All cameras",
                            selected = selectedCameraName == null,
                            initialKey = initialKey,
                            onClick = { onSelected(null) },
                        )
                    }
                }
                items(cameras, key = Camera::name) { camera ->
                    ActivityCameraOption(
                        key = camera.name,
                        label = camera.displayName,
                        selected = camera.name == selectedCameraName,
                        initialKey = initialKey,
                        onClick = { onSelected(camera) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityCameraOption(
    key: String,
    label: String,
    selected: Boolean,
    initialKey: String,
    onClick: () -> Unit,
) {
    FocusCard(
        focusKey = "activity:camera:$key",
        restoreFocusKey = initialKey,
        onFocusRestored = {},
        onClick = onClick,
        selected = selected,
        accessibilityLabel = label,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
        )
    }
}

private fun historyCameraLabel(cameraName: String?, cameras: List<Camera>): String = cameraName
    ?.let { selected -> cameras.firstOrNull { it.name == selected }?.displayName ?: friendlyActivityName(selected) }
    ?: "All cameras"

private fun friendlyActivityName(value: String): String = value
    .replace('_', ' ')
    .trim()
    .replaceFirstChar(Char::uppercase)

private fun formatHistoryHour(epochSeconds: Double): String =
    SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault()).format(Date((epochSeconds * 1000).toLong()))

private fun formatHistoryDay(epochSeconds: Double): String =
    SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date((epochSeconds * 1000).toLong()))

private fun historyDayKey(epochSeconds: Double): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date((epochSeconds * 1000).toLong()))

private fun formatHistoryDayKey(day: String): String {
    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
    val value = parser.parse(day) ?: return day
    return SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(value)
}

private fun recordingHourStart(summary: RecordingHourSummary): Double {
    val parser = SimpleDateFormat("yyyy-MM-dd HH", Locale.US).apply { isLenient = false }
    return parser.parse("${summary.day} ${summary.hour.toString().padStart(2, '0')}")
        ?.time
        ?.div(1_000.0)
        ?: 0.0
}

private fun formatHistoryTime(epochSeconds: Double): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((epochSeconds * 1000).toLong()))

private fun historySlotAccessibilityLabel(slot: HistorySlot): String =
    if (slot.available) "Watch from ${formatHistoryTime(slot.startTime)}" else "No video at ${formatHistoryTime(slot.startTime)}"

private fun searchEventAccessibilityLabel(event: SearchEvent, cameras: List<Camera>): String =
    "${friendlyActivityName(event.label)} at ${historyCameraLabel(event.camera, cameras)}, " +
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date((event.startTime * 1000).toLong()))

private fun ActivitySearchFilters.activeCount(): Int = listOfNotNull(
    cameraName,
    label,
    subLabel,
    zone,
    recognizedLicensePlate,
).size + if (timeRange == ActivitySearchTimeRange.ALL) 0 else 1

private fun ActivitySearchFilters.summary(cameras: List<Camera>): String = buildList {
    cameraName?.let { add(historyCameraLabel(it, cameras)) }
    label?.let { add(friendlyActivityName(it)) }
    subLabel?.let { add(friendlyActivityName(it)) }
    zone?.let { add(friendlyActivityName(it)) }
    recognizedLicensePlate?.let(::add)
    if (timeRange != ActivitySearchTimeRange.ALL) add(timeRange.displayName)
}.joinToString(" • ")

private fun friendlyFilter(value: String?): String = value?.let(::friendlyActivityName) ?: "All"

private fun cycleValue(current: String?, options: List<String>): String? {
    if (options.isEmpty()) return null
    val values = listOf<String?>(null) + options
    return values[(values.indexOf(current).takeIf { it >= 0 } ?: 0).plus(1) % values.size]
}

private fun motionLevelLabel(level: Double): String = when {
    level <= 0.0 -> "Quiet"
    level < 20.0 -> "Some motion"
    else -> "More motion"
}

private fun motionLevelFraction(level: Double): Float = when {
    level <= 0.0 -> 0f
    else -> (level / 60.0).coerceIn(0.08, 1.0).toFloat()
}

private const val MAX_ACTIVITY_SEARCH_LENGTH = 160
private const val ALL_CAMERAS_KEY = "__all_cameras__"
