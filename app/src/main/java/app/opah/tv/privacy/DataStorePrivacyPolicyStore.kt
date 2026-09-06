package app.opah.tv.privacy

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.privacyPolicyDataStore by preferencesDataStore(name = "opah_privacy_policy")

internal sealed interface PrivacyPolicyStoreRead {
    data object Missing : PrivacyPolicyStoreRead
    data class Available(val policy: LocalPrivacyPolicy) : PrivacyPolicyStoreRead
    data object Corrupt : PrivacyPolicyStoreRead
}

internal interface PrivacyPolicyStore {
    suspend fun read(): PrivacyPolicyStoreRead
    suspend fun write(policy: LocalPrivacyPolicy): Boolean
    suspend fun clear(): Boolean
}

/** Atomic, versioned scalar/set persistence; malformed policy is never replaced by permissive defaults. */
internal class DataStorePrivacyPolicyStore(context: Context) : PrivacyPolicyStore {
    private val appContext = context.applicationContext

    override suspend fun read(): PrivacyPolicyStoreRead = runCatching {
        decode(appContext.privacyPolicyDataStore.data.first())
    }.getOrDefault(PrivacyPolicyStoreRead.Corrupt)

    override suspend fun write(policy: LocalPrivacyPolicy): Boolean {
        if (!policy.isValid()) return false
        return runCatching {
            appContext.privacyPolicyDataStore.edit { preferences ->
                preferences.clear()
                preferences[FORMAT_VERSION] = policy.formatVersion.toLong()
                preferences[EPOCH] = policy.epoch
                preferences[GUEST_MODE_ACTIVE] = policy.guestModeActive
                preferences[START_IN_GUEST_MODE] = policy.startInGuestMode
                preferences[PRIVATE_CAMERA_IDS] = policy.privateCameraIds
                preferences[GUEST_MODE_FRIGATE_MODES] = policy.guestModeFrigateModes
                preferences[OWNER_RECOGNITION] = policy.ownerRecognitionDisclosure.name
                preferences[GUEST_RECOGNITION] = policy.guestRecognitionDisclosure.name
                preferences[GLOBAL_NOTIFICATION] = policy.globalNotificationDisclosure.name
                preferences[GUEST_NOTIFICATION] = policy.guestNotificationDisclosure.name
            }
            true
        }.getOrDefault(false)
    }

    override suspend fun clear(): Boolean = runCatching {
        appContext.privacyPolicyDataStore.edit { it.clear() }
        true
    }.getOrDefault(false)

    private fun decode(preferences: Preferences): PrivacyPolicyStoreRead {
        val version = preferences[FORMAT_VERSION]
        if (version == null) {
            return if (preferences.asMap().isEmpty()) {
                PrivacyPolicyStoreRead.Missing
            } else {
                PrivacyPolicyStoreRead.Corrupt
            }
        }
        if (version != LocalPrivacyPolicy.CURRENT_FORMAT_VERSION.toLong()) {
            return PrivacyPolicyStoreRead.Corrupt
        }
        val policy = LocalPrivacyPolicy(
            formatVersion = version.toInt(),
            epoch = preferences[EPOCH] ?: return PrivacyPolicyStoreRead.Corrupt,
            guestModeActive = preferences[GUEST_MODE_ACTIVE] ?: return PrivacyPolicyStoreRead.Corrupt,
            startInGuestMode = preferences[START_IN_GUEST_MODE] ?: return PrivacyPolicyStoreRead.Corrupt,
            privateCameraIds = preferences[PRIVATE_CAMERA_IDS]?.toSet()
                ?: return PrivacyPolicyStoreRead.Corrupt,
            guestModeFrigateModes = preferences[GUEST_MODE_FRIGATE_MODES]?.toSet()
                ?: return PrivacyPolicyStoreRead.Corrupt,
            ownerRecognitionDisclosure = preferences[OWNER_RECOGNITION]
                ?.let { encoded -> RecognitionDisclosure.entries.singleOrNull { it.name == encoded } }
                ?: return PrivacyPolicyStoreRead.Corrupt,
            guestRecognitionDisclosure = preferences[GUEST_RECOGNITION]
                ?.let { encoded -> RecognitionDisclosure.entries.singleOrNull { it.name == encoded } }
                ?: return PrivacyPolicyStoreRead.Corrupt,
            globalNotificationDisclosure = preferences[GLOBAL_NOTIFICATION]
                ?.let { encoded -> NotificationDisclosure.entries.singleOrNull { it.name == encoded } }
                ?: return PrivacyPolicyStoreRead.Corrupt,
            guestNotificationDisclosure = preferences[GUEST_NOTIFICATION]
                ?.let { encoded -> NotificationDisclosure.entries.singleOrNull { it.name == encoded } }
                ?: return PrivacyPolicyStoreRead.Corrupt,
        )
        return if (policy.isValid()) {
            PrivacyPolicyStoreRead.Available(policy)
        } else {
            PrivacyPolicyStoreRead.Corrupt
        }
    }

    private companion object {
        val FORMAT_VERSION = longPreferencesKey("format_version")
        val EPOCH = longPreferencesKey("privacy_epoch")
        val GUEST_MODE_ACTIVE = booleanPreferencesKey("guest_mode_active")
        val START_IN_GUEST_MODE = booleanPreferencesKey("start_in_guest_mode")
        val PRIVATE_CAMERA_IDS = stringSetPreferencesKey("private_camera_ids")
        val GUEST_MODE_FRIGATE_MODES = stringSetPreferencesKey("guest_mode_frigate_modes")
        val OWNER_RECOGNITION = stringPreferencesKey("owner_recognition_disclosure")
        val GUEST_RECOGNITION = stringPreferencesKey("guest_recognition_disclosure")
        val GLOBAL_NOTIFICATION = stringPreferencesKey("global_notification_disclosure")
        val GUEST_NOTIFICATION = stringPreferencesKey("guest_notification_disclosure")
    }
}
