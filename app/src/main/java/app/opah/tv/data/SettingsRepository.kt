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
            preferences[APPEARANCE_MODE] = updated.appearanceMode.name
            preferences[CUSTOM_ACCENT] = updated.customThemeColors.accentArgb
            preferences[CUSTOM_BACKGROUND] = updated.customThemeColors.backgroundArgb
            preferences[STRETCHED_CAMERA_NAMES] = updated.stretchedCameraNames
            preferences[SAVED_CAMERA_VIEWS] = json.encodeToString(
                updated.savedCameraViews.map(StoredCameraView::from),
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
        )

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

    private companion object {
        val STREAM_PREFERENCE = stringPreferencesKey("stream_preference")
        val PREFER_RTP_TCP = booleanPreferencesKey("prefer_rtp_tcp")
        val START_LIVE_MUTED = booleanPreferencesKey("start_live_muted")
        val DIAGNOSTICS_ENABLED = booleanPreferencesKey("diagnostics_enabled")
        val APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
        val CUSTOM_ACCENT = intPreferencesKey("custom_accent")
        val CUSTOM_BACKGROUND = intPreferencesKey("custom_background")
        val STRETCHED_CAMERA_NAMES = stringSetPreferencesKey("stretched_camera_names")
        val SAVED_CAMERA_VIEWS = stringPreferencesKey("saved_camera_views")
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
