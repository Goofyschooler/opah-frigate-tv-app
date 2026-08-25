package app.opah.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.BuildConfig
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.StreamPreference
import app.opah.tv.data.model.AppSettings
import app.opah.tv.data.model.StartupTarget
import app.opah.tv.data.model.StartupTargetKind
import app.opah.tv.data.update.AppVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class SettingsPage(
    val label: String,
    val description: String,
) {
    MAIN("Settings", ""),
    UPDATE("Update", "See whether a newer version of Opah is ready"),
    APPEARANCE("Appearance", "Choose how Opah looks"),
    PLAYBACK("Playback", "Choose how video starts and plays"),
    STARTUP("Startup", "Choose what Opah opens first"),
    CONNECTION("Connection", "Manage this device's connection"),
    SYSTEM("System", "See performance and recording space"),
    ADVANCED("Advanced", "Technical details for troubleshooting"),
    ABOUT("About Opah", "Project, privacy, and legal information"),
}

internal val SETTINGS_PAGE_ORDER = listOf(
    SettingsPage.UPDATE,
    SettingsPage.APPEARANCE,
    SettingsPage.PLAYBACK,
    SettingsPage.STARTUP,
    SettingsPage.CONNECTION,
    SettingsPage.SYSTEM,
    SettingsPage.ADVANCED,
    SettingsPage.ABOUT,
)

@Composable
internal fun SettingsHubScreen(
    update: AppUpdateUiState,
    settings: AppSettings,
    restorePage: SettingsPage?,
    initialFocusRequester: FocusRequester,
    onFocusRestored: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Make Opah work the way you want",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(SETTINGS_PAGE_ORDER, key = SettingsPage::name) { page ->
            val focusKey = "settings:${page.name.lowercase()}"
            SettingsRow(
                focusKey = focusKey,
                title = page.label,
                value = settingsPageValue(page, update, settings),
                restoreFocusKey = restorePage?.let { "settings:${it.name.lowercase()}" },
                onFocusRestored = onFocusRestored,
                onClick = { onOpen(page) },
                accessibilityLabel = settingsPageAccessibilityLabel(page, update.updateAvailable),
                externalFocusRequester = initialFocusRequester.takeIf { page == SettingsPage.UPDATE },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun settingsPageValue(
    page: SettingsPage,
    update: AppUpdateUiState,
    settings: AppSettings,
): String? = when (page) {
    SettingsPage.UPDATE -> when {
        update.updateAvailable -> "Ready"
        update.checking -> "Checking"
        update.errorMessage != null -> "Check failed"
        update.checkedOnce -> "Current"
        settings.automaticUpdateChecksEnabled -> "Will check"
        else -> "Manual"
    }
    SettingsPage.APPEARANCE -> settings.appearanceMode.name.lowercase().replaceFirstChar(Char::uppercase)
    SettingsPage.PLAYBACK -> settings.streamPreference.name.lowercase()
        .replace('_', ' ')
        .replaceFirstChar(Char::uppercase)
    SettingsPage.STARTUP -> startupTargetLabel(settings.startupTarget, settings)
    SettingsPage.CONNECTION -> "Connected"
    SettingsPage.SYSTEM -> "Status"
    SettingsPage.ADVANCED,
    SettingsPage.ABOUT,
    SettingsPage.MAIN,
    -> null
}

internal fun startupTargetLabel(target: StartupTarget, settings: AppSettings): String = when (target.kind) {
    StartupTargetKind.HOME -> "Home"
    StartupTargetKind.LAST_VIEWED -> "Last viewed"
    StartupTargetKind.BIRDSEYE -> "All Cameras"
    StartupTargetKind.CAMERA -> target.value ?: "Home"
    StartupTargetKind.SAVED_VIEW -> settings.savedCameraViews
        .firstOrNull { it.id == target.value }
        ?.name
        ?: "Home"
    StartupTargetKind.CAMERA_GROUP -> target.value ?: "Home"
}

@Composable
internal fun StartupSettingsScreen(
    state: Phase0UiState,
    onTarget: (StartupTarget) -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    val settings = state.settings
    val snapshot = state.snapshot
    SettingsDetailColumn(
        title = "Startup",
        subtitle = "Choose what Opah opens after connecting",
        onBack = onBack,
    ) {
        SettingsSection("Start with", "Camera shortcuts and deep links still take priority") {
            ChoiceRow(
                focusKey = "settings:startup:home",
                title = "Home",
                selected = settings.startupTarget.kind == StartupTargetKind.HOME,
                onClick = { onTarget(StartupTarget()) },
                externalFocusRequester = initialFocusRequester,
            )
            ChoiceRow(
                focusKey = "settings:startup:last",
                title = "Last viewed camera or view",
                selected = settings.startupTarget.kind == StartupTargetKind.LAST_VIEWED,
                onClick = { onTarget(StartupTarget(StartupTargetKind.LAST_VIEWED)) },
            )
            if (snapshot?.birdseye?.playable == true) {
                ChoiceRow(
                    focusKey = "settings:startup:birdseye",
                    title = "All Cameras",
                    selected = settings.startupTarget.kind == StartupTargetKind.BIRDSEYE,
                    onClick = { onTarget(StartupTarget(StartupTargetKind.BIRDSEYE)) },
                )
            }
        }
        if (snapshot?.cameras?.isNotEmpty() == true) {
            SettingsSection("Camera", "Open one camera immediately") {
                snapshot.cameras.forEach { camera ->
                    val target = StartupTarget(StartupTargetKind.CAMERA, camera.name)
                    ChoiceRow(
                        focusKey = "settings:startup:camera:${camera.name}",
                        title = camera.displayName,
                        selected = settings.startupTarget == target,
                        onClick = { onTarget(target) },
                    )
                }
            }
        }
        val savedViews = settings.savedCameraViews
        val groups = snapshot?.cameraGroups.orEmpty()
        if (savedViews.isNotEmpty() || groups.isNotEmpty()) {
            SettingsSection("View", "Open a saved view or Frigate camera group") {
                savedViews.forEach { view ->
                    val target = StartupTarget(StartupTargetKind.SAVED_VIEW, view.id)
                    ChoiceRow(
                        focusKey = "settings:startup:view:${view.id}",
                        title = view.name,
                        selected = settings.startupTarget == target,
                        onClick = { onTarget(target) },
                    )
                }
                groups.forEach { group ->
                    val target = StartupTarget(StartupTargetKind.CAMERA_GROUP, group.name)
                    ChoiceRow(
                        focusKey = "settings:startup:group:${group.name}",
                        title = group.displayName,
                        selected = settings.startupTarget == target,
                        onClick = { onTarget(target) },
                    )
                }
            }
        }
    }
}

internal fun settingsPageAccessibilityLabel(page: SettingsPage, updateAvailable: Boolean): String =
    if (page == SettingsPage.UPDATE && updateAvailable) {
        "Update, update available"
    } else {
        page.label
    }

internal enum class UpdateInitialFocusTarget { INSTALL, DOWNLOAD, CHECK, AUTOMATIC }

internal fun updateInitialFocusTarget(update: AppUpdateUiState): UpdateInitialFocusTarget = when {
    update.preparedApkPath != null -> UpdateInitialFocusTarget.INSTALL
    update.updateAvailable && !update.downloading -> UpdateInitialFocusTarget.DOWNLOAD
    !update.checking && !update.downloading -> UpdateInitialFocusTarget.CHECK
    else -> UpdateInitialFocusTarget.AUTOMATIC
}

@Composable
internal fun UpdateSettingsScreen(
    update: AppUpdateUiState,
    automaticChecksEnabled: Boolean,
    onBack: () -> Unit,
    onCheckAgain: () -> Unit,
    onAutomaticChecks: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onInstall: (String) -> Unit,
    initialFocusRequester: FocusRequester,
) {
    val initialFocusTarget = updateInitialFocusTarget(update)
    SettingsDetailColumn(
        title = "Update",
        subtitle = "Keep Opah up to date",
        onBack = onBack,
    ) {
        SettingsSection(
            title = appUpdateStatusTitle(update),
            subtitle = "Nothing downloads or installs automatically",
        ) {
            ReadOnlyValue("Installed", installedAppVersionLabel())
            update.latestVersion?.let { ReadOnlyValue("Available", it) }
            update.errorMessage?.let { ScreenMessage(it, isError = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (update.preparedApkPath != null) {
                    PrimaryAction(
                        focusKey = "settings:update:install",
                        label = "Install now",
                        onClick = { onInstall(update.preparedApkPath) },
                        externalFocusRequester = initialFocusRequester.takeIf {
                            initialFocusTarget == UpdateInitialFocusTarget.INSTALL
                        },
                    )
                } else if (update.updateAvailable) {
                    PrimaryAction(
                        focusKey = "settings:update:download",
                        label = if (update.downloading) "Downloading…" else "Download update",
                        onClick = onDownload,
                        enabled = !update.downloading,
                        externalFocusRequester = initialFocusRequester.takeIf {
                            initialFocusTarget == UpdateInitialFocusTarget.DOWNLOAD
                        },
                    )
                }
                SecondaryAction(
                    focusKey = "settings:update:check",
                    label = if (update.checking) "Checking…" else "Check again",
                    onClick = onCheckAgain,
                    enabled = !update.checking,
                    externalFocusRequester = initialFocusRequester.takeIf {
                        initialFocusTarget == UpdateInitialFocusTarget.CHECK
                    },
                )
            }
        }
        if (update.preparedApkPath != null) {
            Text(
                "Android will ask you to confirm the update. If prompted, allow Opah to install apps, return here, and choose Install now again",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (automaticChecksEnabled && !update.checkedOnce && !update.checking) {
            Text(
                "Opah will check quietly after it finishes starting",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsSection(
            title = "Automatic checks",
            subtitle = "Choose whether Opah checks after it starts",
        ) {
            SettingToggle(
                label = "Check automatically",
                value = automaticChecksEnabled,
                onChange = onAutomaticChecks,
                externalFocusRequester = initialFocusRequester.takeIf {
                    initialFocusTarget == UpdateInitialFocusTarget.AUTOMATIC
                },
            )
        }
        releaseNotesForDisplay(update.releaseNotes)?.let { notes ->
            SettingsSection(
                title = "What’s new",
                subtitle = update.latestVersion?.let { "Opah $it" },
            ) {
                ScrollableReleaseNotes(notes)
            }
        }
    }
}

@Composable
private fun ScrollableReleaseNotes(notes: String) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scrollStep = with(LocalDensity.current) { 180.dp.roundToPx() }
    FocusCard(
        focusKey = "settings:update:release-notes",
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = {
            if (scrollState.value < scrollState.maxValue) {
                scope.launch {
                    scrollState.scrollTo((scrollState.value + scrollStep).coerceAtMost(scrollState.maxValue))
                }
            }
        },
        accessibilityLabel = "Release notes. Use up and down to read",
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val target = when (event.key) {
                    Key.DirectionDown -> (scrollState.value + scrollStep).coerceAtMost(scrollState.maxValue)
                    Key.DirectionUp -> (scrollState.value - scrollStep).coerceAtLeast(0)
                    else -> return@onPreviewKeyEvent false
                }
                if (target == scrollState.value) return@onPreviewKeyEvent false
                scope.launch { scrollState.scrollTo(target) }
                true
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                notes,
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(scrollState),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (scrollState.maxValue > 0) {
                Text(
                    "Use up and down to read",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal fun releaseNotesForDisplay(rawNotes: String): String? {
    val markdownLink = Regex("""!?\[([^]]+)]\([^)]+\)""")
    val simpleFormatting = Regex("""[*_`]""")
    val htmlTag = Regex("""<[^>]+>""")
    val cleanedLines = rawNotes
        .lineSequence()
        .map { line ->
            line.trim()
                .replace(Regex("""^#{1,6}\s+"""), "")
                .replace(Regex("""^[-+*]\s+"""), "• ")
                .replace(markdownLink, "$1")
                .replace(simpleFormatting, "")
                .replace(htmlTag, "")
                .trim()
        }
        .fold(mutableListOf<String>()) { lines, line ->
            if (line.isNotEmpty() || lines.lastOrNull()?.isNotEmpty() == true) lines += line
            lines
        }
        .dropLastWhile(String::isEmpty)
        .take(MAX_RELEASE_NOTE_LINES)
        .joinToString("\n")
        .take(MAX_RELEASE_NOTE_DISPLAY_LENGTH)
        .trim()
    return cleanedLines.takeIf(String::isNotEmpty)
}

private const val MAX_RELEASE_NOTE_LINES = 160
private const val MAX_RELEASE_NOTE_DISPLAY_LENGTH = 16_000

internal fun installedAppVersionLabel(versionName: String = BuildConfig.VERSION_NAME): String =
    AppVersion.parse(versionName)?.canonical ?: versionName

internal fun appUpdateStatusTitle(update: AppUpdateUiState): String = when {
    update.updateAvailable -> "Update available"
    update.checking -> "Checking for an update…"
    update.errorMessage != null -> "Couldn't check for an update"
    update.checkedOnce -> "Opah is up to date"
    else -> "Check for an update"
}

@Composable
internal fun AppearanceSettingsScreen(
    state: Phase0UiState,
    onAppearance: (AppearanceMode) -> Unit,
    onCustomTheme: (CustomThemeColors) -> Unit,
    onReducedMotion: (Boolean) -> Unit,
    onHighContrast: (Boolean) -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    SettingsDetailColumn(
        title = "Appearance",
        subtitle = "Choose how Opah looks",
        onBack = onBack,
    ) {
        SettingsSection("Theme", "Use the device setting, or choose light or dark") {
            ChoiceRow(
                values = AppearanceMode.entries,
                selected = state.settings.appearanceMode,
                label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                keyPrefix = "settings:appearance",
                onSelect = onAppearance,
                externalFocusRequester = initialFocusRequester,
            )
        }
        if (state.settings.appearanceMode == AppearanceMode.CUSTOM) {
            SettingsSection(
                "Color style",
                "Start with a style, then use left and right to make it yours",
            ) {
                ThemeColorEditor(state.settings.customThemeColors, onCustomTheme)
            }
        }
        SettingsSection("Accessibility", "Adjust motion and visual contrast") {
            SettingToggle("Reduce motion", state.settings.reducedMotion, onReducedMotion)
            SettingToggle("High contrast", state.settings.highContrast, onHighContrast)
        }
    }
}

@Composable
internal fun PlaybackSettingsScreen(
    state: Phase0UiState,
    onStreamPreference: (StreamPreference) -> Unit,
    onCompatibilityMode: (Boolean) -> Unit,
    onStartMuted: (Boolean) -> Unit,
    onPlaybackDetails: (Boolean) -> Unit,
    onAutoMarkReviewed: (Boolean) -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    SettingsDetailColumn(
        title = "Playback",
        subtitle = "Choose how video starts and plays",
        onBack = onBack,
    ) {
        SettingsSection("Default live video", "Automatic works best for most people") {
            ChoiceRow(
                values = StreamPreference.entries,
                selected = state.settings.streamPreference,
                label = {
                    when (it) {
                        StreamPreference.AUTOMATIC -> "Automatic"
                        StreamPreference.MAIN -> "Best quality"
                        StreamPreference.LOW_BANDWIDTH -> "Low bandwidth"
                    }
                },
                keyPrefix = "settings:stream",
                onSelect = onStreamPreference,
                externalFocusRequester = initialFocusRequester,
            )
        }
        SettingsSection("When video opens", "These choices apply the next time video opens") {
            SettingToggle("Compatibility mode", state.settings.preferRtpTcp, onCompatibilityMode)
            SettingToggle("Start live video muted", state.settings.startLiveMuted, onStartMuted)
            SettingToggle("Show playback details", state.settings.diagnosticsEnabled, onPlaybackDetails)
            SettingToggle(
                "Mark activity reviewed after playback",
                state.settings.autoMarkReviewedAfterPlayback,
                onAutoMarkReviewed,
            )
        }
    }
}

@Composable
internal fun ConnectionSettingsScreen(
    state: Phase0UiState,
    onUpdateLiveRoute: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onForgetServer: () -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    val profile = state.activeProfile ?: return
    var liveHost by rememberSaveable(profile) { mutableStateOf(profile.rtspHostOverride.orEmpty()) }
    var livePort by rememberSaveable(profile) { mutableStateOf(profile.rtspPort.toString()) }
    val inputFocusCoordinator = remember { TvInputFocusCoordinator() }
    val saveFocusRequester = remember { FocusRequester() }
    SettingsDetailColumn(
        title = "Connection",
        subtitle = "Manage this device's connection",
        onBack = onBack,
    ) {
        SettingsSection("Account", "The server and account Opah uses on this device") {
            ReadOnlyValue("Server", profile.apiBaseUrl)
            ReadOnlyValue("Account", profile.username)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryAction(
                    focusKey = "settings:connection:sign-out",
                    label = "Sign out",
                    onClick = onSignOut,
                    externalFocusRequester = initialFocusRequester,
                )
                SecondaryAction(
                    focusKey = "settings:connection:forget",
                    label = "Forget connection",
                    onClick = onForgetServer,
                )
            }
        }
        SettingsSection("Live video route", "Only change this if live video cannot connect") {
            ProductionTvInput(
                label = "Live video host (optional)",
                value = liveHost,
                onValueChange = { liveHost = it },
                placeholder = "Use the same server",
                enabled = true,
                inputKey = "settings:live-host",
                focusCoordinator = inputFocusCoordinator,
                nextInputKey = "settings:live-port",
            )
            ProductionTvInput(
                label = "Live video port",
                value = livePort,
                onValueChange = { livePort = it.filter(Char::isDigit) },
                placeholder = "8554",
                enabled = true,
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                inputKey = "settings:live-port",
                focusCoordinator = inputFocusCoordinator,
                previousInputKey = "settings:live-host",
                nextFocusRequester = saveFocusRequester,
            )
            Button(
                onClick = { onUpdateLiveRoute(liveHost, livePort) },
                modifier = Modifier.focusRequester(saveFocusRequester),
            ) { Text("Save live video route") }
        }
    }
}

@Composable
private fun SettingsDetailColumn(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
    }
}

@Composable
internal fun SafeBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocusRequester: FocusRequester? = null,
    label: String = "Back",
) {
    var focused by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        armed = false
        if (focused) {
            delay(400)
            armed = true
        }
    }
    Button(
        onClick = { if (armed) onClick() },
        modifier = modifier
            .then(
                if (initialFocusRequester == null) Modifier else Modifier.focusRequester(initialFocusRequester),
            )
            .onFocusChanged { focused = it.isFocused },
    ) { Text(label) }
}
