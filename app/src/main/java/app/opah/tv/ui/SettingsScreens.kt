package app.opah.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import app.opah.tv.data.update.AppVersion
import kotlinx.coroutines.delay

internal enum class SettingsPage(
    val label: String,
    val description: String,
) {
    MAIN("Settings", ""),
    UPDATE("Update", "See whether a newer version of Opah is ready"),
    APPEARANCE("Appearance", "Choose how Opah looks"),
    PLAYBACK("Playback", "Choose how video starts and plays"),
    CONNECTION("Connection", "Manage this device's connection"),
    SYSTEM("System", "See performance and recording space"),
    ADVANCED("Advanced", "Technical details for troubleshooting"),
    ABOUT("About Opah", "Project, privacy, and legal information"),
}

internal val SETTINGS_PAGE_ORDER = listOf(
    SettingsPage.UPDATE,
    SettingsPage.APPEARANCE,
    SettingsPage.PLAYBACK,
    SettingsPage.CONNECTION,
    SettingsPage.SYSTEM,
    SettingsPage.ADVANCED,
    SettingsPage.ABOUT,
)

@Composable
internal fun SettingsHubScreen(
    update: AppUpdateUiState,
    restorePage: SettingsPage?,
    initialFocusRequester: FocusRequester,
    onFocusRestored: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
            FocusCard(
                focusKey = focusKey,
                restoreFocusKey = restorePage?.let { "settings:${it.name.lowercase()}" },
                onFocusRestored = onFocusRestored,
                onClick = { onOpen(page) },
                accessibilityLabel = settingsPageAccessibilityLabel(page, update.updateAvailable),
                externalFocusRequester = initialFocusRequester.takeIf { page == SettingsPage.UPDATE },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(page.label, fontWeight = FontWeight.Bold)
                        Text(
                            page.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        if (page == SettingsPage.UPDATE && update.updateAvailable) "Update ready  ↓" else "Open",
                        color = if (page == SettingsPage.UPDATE && update.updateAvailable) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                        fontWeight = FontWeight.Bold,
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

@Composable
internal fun UpdateSettingsScreen(
    update: AppUpdateUiState,
    onBack: () -> Unit,
    onCheckAgain: () -> Unit,
    onDownload: () -> Unit,
    onInstall: (String) -> Unit,
    initialFocusRequester: FocusRequester,
) {
    SettingsDetailColumn(
        title = "Update",
        subtitle = "Keep Opah up to date",
        onBack = onBack,
        initialFocusRequester = initialFocusRequester,
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
                    Button(
                        onClick = { onInstall(update.preparedApkPath) },
                    ) { Text("Install now") }
                } else if (update.updateAvailable) {
                    Button(onClick = onDownload, enabled = !update.downloading) {
                        Text(if (update.downloading) "Downloading…" else "Download update")
                    }
                }
                Button(onClick = onCheckAgain, enabled = !update.checking) {
                    Text(if (update.checking) "Checking…" else "Check again")
                }
            }
        }
        if (update.preparedApkPath != null) {
            Text(
                "Android will ask you to confirm the update. If prompted, allow Opah to install apps, return here, and choose Install now again",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!update.checkedOnce && !update.checking) {
            Text(
                "Opah checks for a new release when the app opens",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        releaseNotesForDisplay(update.releaseNotes)?.let { notes ->
            SettingsSection(
                title = "What’s new",
                subtitle = update.latestVersion?.let { "Opah $it" },
                isFocusable = true,
            ) {
                Text(notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private const val MAX_RELEASE_NOTE_LINES = 24
private const val MAX_RELEASE_NOTE_DISPLAY_LENGTH = 3_000

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
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    SettingsDetailColumn(
        title = "Appearance",
        subtitle = "Choose how Opah looks",
        onBack = onBack,
        initialFocusRequester = initialFocusRequester,
    ) {
        SettingsSection("Theme", "Use the device setting, or choose light or dark") {
            ChoiceRow(
                values = AppearanceMode.entries,
                selected = state.settings.appearanceMode,
                label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                keyPrefix = "settings:appearance",
                onSelect = onAppearance,
            )
        }
        if (state.settings.appearanceMode == AppearanceMode.CUSTOM) {
            SettingsSection(
                "Color style",
                "Choose a color combination",
            ) {
                ThemeColorEditor(state.settings.customThemeColors, onCustomTheme)
            }
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
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    SettingsDetailColumn(
        title = "Playback",
        subtitle = "Choose how video starts and plays",
        onBack = onBack,
        initialFocusRequester = initialFocusRequester,
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
            )
        }
        SettingsSection("When video opens", "These choices apply the next time video opens") {
            SettingToggle("Compatibility mode", state.settings.preferRtpTcp, onCompatibilityMode)
            SettingToggle("Start live video muted", state.settings.startLiveMuted, onStartMuted)
            SettingToggle("Show playback details", state.settings.diagnosticsEnabled, onPlaybackDetails)
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
        initialFocusRequester = initialFocusRequester,
    ) {
        SettingsSection("Account", "The server and account Opah uses on this device") {
            ReadOnlyValue("Server", profile.apiBaseUrl)
            ReadOnlyValue("Account", profile.username)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSignOut) { Text("Sign out") }
                Button(onClick = onForgetServer) { Text("Forget connection") }
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
    initialFocusRequester: FocusRequester,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SafeBackButton(
                    onClick = onBack,
                    initialFocusRequester = initialFocusRequester,
                )
                Column {
                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
