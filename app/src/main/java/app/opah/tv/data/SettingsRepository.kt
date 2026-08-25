package app.opah.tv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.opah.tv.data.model.AppSettings
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.SavedCameraView
import app.opah.tv.data.model.ThemeColorPolicy
import app.opah.tv.data.model.StreamPreference
import app.opah.tv.data.model.StartupTarget
import app.opah.tv.data.model.StartupTargetKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(name = "opah_settings")

class SettingsRepository(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map(::decode)

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { preferences ->
            val updated = transform(decode(preferences))
            preferences[STREAM_PREFERENCE] = updated.streamPreference.name
            preferences[PREFER_RTP_TCP] = updated.preferRtpTcp
            preferences[START_LIVE_MUTED] = updated.startLiveMuted
            preferences[DIAGNOSTICS_ENABLED] = updated.diagnosticsEnabled
            preferences[AUTOMATIC_UPDATE_CHECKS] = updated.automaticUpdateChecksEnabled
            preferences[APPEARANCE_MODE] = updated.appearanceMode.name
            preferences[CUSTOM_ACCENT] = updated.customThemeColors.accentArgb
            preferences[CUSTOM_BACKGROUND] = updated.customThemeColors.backgroundArgb
            preferences[STRETCHED_CAMERA_NAMES] = updated.stretchedCameraNames
            preferences[SAVED_CAMERA_VIEWS] = json.encodeToString(
                updated.savedCameraViews.map(StoredCameraView::from),
            )
            preferences[REDUCED_MOTION] = updated.reducedMotion
            preferences[HIGH_CONTRAST] = updated.highContrast
            preferences[FAVORITE_CAMERA_NAMES] = json.encodeToString(updated.favoriteCameraNames)
            preferences[HIDDEN_HOME_CAMERA_NAMES] = updated.hiddenHomeCameraNames
            preferences[FAVORITE_VIEW_IDS] = json.encodeToString(updated.favoriteViewIds)
            preferences[STARTUP_TARGET] = json.encodeToString(StoredStartupTarget.from(updated.startupTarget))
            updated.lastViewedTarget?.let { target ->
                preferences[LAST_VIEWED_TARGET] = json.encodeToString(StoredStartupTarget.from(target))
            } ?: preferences.remove(LAST_VIEWED_TARGET)
            preferences[AUTO_MARK_REVIEWED_AFTER_PLAYBACK] = updated.autoMarkReviewedAfterPlayback
            preferences[RECENT_ACTIVITY_SEARCHES] = json.encodeToString(
                sanitizeRecentActivitySearches(updated.recentActivitySearches),
            )
        }
    }

    suspend fun reset() {
        context.settingsDataStore.edit { it.clear() }
    }

    private fun decode(preferences: androidx.datastore.preferences.core.Preferences): AppSettings =
        AppSettings(
            streamPreference = preferences[STREAM_PREFERENCE]
                ?.let { runCatching { StreamPreference.valueOf(it) }.getOrNull() }
                ?: StreamPreference.AUTOMATIC,
            preferRtpTcp = preferences[PREFER_RTP_TCP] ?: true,
            startLiveMuted = preferences[START_LIVE_MUTED] ?: false,
            diagnosticsEnabled = preferences[DIAGNOSTICS_ENABLED] ?: true,
            automaticUpdateChecksEnabled = preferences[AUTOMATIC_UPDATE_CHECKS] ?: true,
            appearanceMode = preferences[APPEARANCE_MODE]
                ?.let { runCatching { AppearanceMode.valueOf(it) }.getOrNull() }
                ?: AppearanceMode.SYSTEM,
            customThemeColors = ThemeColorPolicy.sanitize(
                CustomThemeColors(
                    accentArgb = preferences[CUSTOM_ACCENT] ?: CustomThemeColors().accentArgb,
                    backgroundArgb = preferences[CUSTOM_BACKGROUND] ?: CustomThemeColors().backgroundArgb,
                ),
            ),
            stretchedCameraNames = preferences[STRETCHED_CAMERA_NAMES]?.toSet().orEmpty(),
            savedCameraViews = preferences[SAVED_CAMERA_VIEWS]
                ?.let { encoded ->
                    runCatching { json.decodeFromString<List<StoredCameraView>>(encoded) }
                        .getOrDefault(emptyList())
                }
                .orEmpty()
                .map(StoredCameraView::toModel)
                .let(::sanitizeSavedCameraViews),
            reducedMotion = preferences[REDUCED_MOTION] ?: false,
            highContrast = preferences[HIGH_CONTRAST] ?: false,
            favoriteCameraNames = preferences[FAVORITE_CAMERA_NAMES]
                ?.let(::decodeIdentifierOrder)
                .orEmpty(),
            hiddenHomeCameraNames = preferences[HIDDEN_HOME_CAMERA_NAMES]
                ?.let(::sanitizeHomeIdentifierOrder)
                ?.toSet()
                .orEmpty(),
            favoriteViewIds = preferences[FAVORITE_VIEW_IDS]
                ?.let(::decodeIdentifierOrder)
                .orEmpty(),
            startupTarget = preferences[STARTUP_TARGET]
                ?.let(::decodeStartupTarget)
                ?: StartupTarget(),
            lastViewedTarget = preferences[LAST_VIEWED_TARGET]
                ?.let(::decodeStartupTarget)
                ?.let { sanitizeStartupTarget(it, allowLastViewed = false) },
            autoMarkReviewedAfterPlayback = preferences[AUTO_MARK_REVIEWED_AFTER_PLAYBACK] ?: false,
            recentActivitySearches = preferences[RECENT_ACTIVITY_SEARCHES]
                ?.let { encoded ->
                    runCatching { json.decodeFromString<List<String>>(encoded) }.getOrDefault(emptyList())
                }
                ?.let(::sanitizeRecentActivitySearches)
                .orEmpty(),
        )

    private fun decodeIdentifierOrder(encoded: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(encoded) }
            .getOrDefault(emptyList())
            .let(::sanitizeHomeIdentifierOrder)

    private fun decodeStartupTarget(encoded: String): StartupTarget? =
        runCatching { json.decodeFromString<StoredStartupTarget>(encoded).toModel() }
            .getOrNull()
            ?.let { sanitizeStartupTarget(it) }

    @Serializable
    private data class StoredCameraView(
        val id: String,
        val name: String,
        val firstCameraName: String,
        val secondCameraName: String,
        val thirdCameraName: String? = null,
        val fourthCameraName: String? = null,
    ) {
        fun toModel() = SavedCameraView(
            id,
            name,
            firstCameraName,
            secondCameraName,
            thirdCameraName,
            fourthCameraName,
        )

        companion object {
            fun from(view: SavedCameraView) = StoredCameraView(
                view.id,
                view.name,
                view.firstCameraName,
                view.secondCameraName,
                view.thirdCameraName,
                view.fourthCameraName,
            )
        }
    }

    @Serializable
    private data class StoredStartupTarget(
        val kind: String,
        val value: String? = null,
    ) {
        fun toModel(): StartupTarget? = runCatching {
            StartupTarget(StartupTargetKind.valueOf(kind), value)
        }.getOrNull()

        companion object {
            fun from(target: StartupTarget) = StoredStartupTarget(target.kind.name, target.value)
        }
    }

    private companion object {
        val STREAM_PREFERENCE = stringPreferencesKey("stream_preference")
        val PREFER_RTP_TCP = booleanPreferencesKey("prefer_rtp_tcp")
        val START_LIVE_MUTED = booleanPreferencesKey("start_live_muted")
        val DIAGNOSTICS_ENABLED = booleanPreferencesKey("diagnostics_enabled")
        val AUTOMATIC_UPDATE_CHECKS = booleanPreferencesKey("automatic_update_checks")
        val APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
        val CUSTOM_ACCENT = intPreferencesKey("custom_accent")
        val CUSTOM_BACKGROUND = intPreferencesKey("custom_background")
        val STRETCHED_CAMERA_NAMES = stringSetPreferencesKey("stretched_camera_names")
        val SAVED_CAMERA_VIEWS = stringPreferencesKey("saved_camera_views")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val FAVORITE_CAMERA_NAMES = stringPreferencesKey("favorite_camera_names")
        val HIDDEN_HOME_CAMERA_NAMES = stringSetPreferencesKey("hidden_home_camera_names")
        val FAVORITE_VIEW_IDS = stringPreferencesKey("favorite_view_ids")
        val STARTUP_TARGET = stringPreferencesKey("startup_target")
        val LAST_VIEWED_TARGET = stringPreferencesKey("last_viewed_target")
        val AUTO_MARK_REVIEWED_AFTER_PLAYBACK = booleanPreferencesKey("auto_mark_reviewed_after_playback")
        val RECENT_ACTIVITY_SEARCHES = stringPreferencesKey("recent_activity_searches")
    }
}

internal fun sanitizeRecentActivitySearches(values: Collection<String>): List<String> =
    values.asSequence()
        .map { it.trim().replace(Regex("\\s+"), " ").take(MAX_RECENT_SEARCH_LENGTH) }
        .filter { it.isNotEmpty() && it.none(Char::isISOControl) }
        .distinctBy(String::lowercase)
        .take(MAX_RECENT_SEARCHES)
        .toList()

internal fun sanitizeHomeIdentifierOrder(values: Collection<String>): List<String> =
    values.asSequence()
        .map(String::trim)
        .filter { value ->
            value.isNotEmpty() && value.length <= MAX_HOME_IDENTIFIER_LENGTH && value.none(Char::isISOControl)
        }
        .distinct()
        .take(MAX_HOME_IDENTIFIERS)
        .toList()

internal fun sanitizeStartupTarget(
    target: StartupTarget,
    allowLastViewed: Boolean = true,
): StartupTarget? = when (target.kind) {
    StartupTargetKind.HOME,
    StartupTargetKind.BIRDSEYE,
    -> StartupTarget(target.kind)
    StartupTargetKind.LAST_VIEWED -> if (allowLastViewed) StartupTarget(target.kind) else null
    StartupTargetKind.CAMERA,
    StartupTargetKind.SAVED_VIEW,
    StartupTargetKind.CAMERA_GROUP,
    -> sanitizeHomeIdentifierOrder(listOfNotNull(target.value)).singleOrNull()?.let { value ->
        StartupTarget(target.kind, value)
    }
}

internal fun sanitizeSavedCameraViews(views: List<SavedCameraView>): List<SavedCameraView> =
    views.mapNotNull { view ->
        val id = view.id.trim().take(MAX_SAVED_VIEW_ID_LENGTH)
        val name = view.name.trim().replace(Regex("\\s+"), " ").take(MAX_SAVED_VIEW_NAME_LENGTH)
        val cameras = view.cameraNames
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (id.isEmpty() || name.isEmpty() || cameras.size !in MIN_CAMERAS_PER_VIEW..MAX_CAMERAS_PER_VIEW) {
            null
        } else {
            SavedCameraView(
                id = id,
                name = name,
                firstCameraName = cameras[0],
                secondCameraName = cameras[1],
                thirdCameraName = cameras.getOrNull(2),
                fourthCameraName = cameras.getOrNull(3),
            )
        }
    }.distinctBy(SavedCameraView::id)
        .takeLast(MAX_SAVED_CAMERA_VIEWS)

private const val MAX_SAVED_VIEW_ID_LENGTH = 80
private const val MAX_SAVED_VIEW_NAME_LENGTH = 40
private const val MAX_SAVED_CAMERA_VIEWS = 20
private const val MIN_CAMERAS_PER_VIEW = 2
private const val MAX_CAMERAS_PER_VIEW = 4
private const val MAX_HOME_IDENTIFIER_LENGTH = 256
private const val MAX_HOME_IDENTIFIERS = 100
private const val MAX_RECENT_SEARCH_LENGTH = 120
private const val MAX_RECENT_SEARCHES = 8
