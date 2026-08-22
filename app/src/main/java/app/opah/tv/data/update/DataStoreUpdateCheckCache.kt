package app.opah.tv.data.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.updateCheckDataStore by preferencesDataStore(name = "opah_update_check")

class DataStoreUpdateCheckCache(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : UpdateCheckCache {
    override suspend fun read(): CachedAppRelease? {
        val encoded = context.updateCheckDataStore.data.first()[LATEST_RELEASE] ?: return null
        return runCatching { json.decodeFromString<StoredUpdateCheck>(encoded).toModel() }
            .getOrNull()
    }

    override suspend fun write(value: CachedAppRelease) {
        val encoded = json.encodeToString(StoredUpdateCheck.from(value))
        context.updateCheckDataStore.edit { preferences ->
            preferences[LATEST_RELEASE] = encoded
        }
    }

    @Serializable
    private data class StoredUpdateCheck(
        val schema: Int,
        val checkedAtEpochMillis: Long,
        val tagName: String,
        val major: Int,
        val minor: Int,
        val patch: Int,
        val releasePageUrl: String,
        val releaseNotes: String = "",
        val apkName: String,
        val apkDownloadUrl: String,
        val apkSizeBytes: Long,
        val apkSha256Digest: String?,
        val checksumName: String,
        val checksumDownloadUrl: String,
        val checksumSizeBytes: Long,
        val checksumSha256Digest: String?,
    ) {
        fun toModel(): CachedAppRelease {
            require(schema == CURRENT_SCHEMA)
            return CachedAppRelease(
                checkedAtEpochMillis = checkedAtEpochMillis,
                release = AppRelease(
                    tagName = tagName,
                    version = AppVersion(major, minor, patch),
                    releasePageUrl = releasePageUrl,
                    releaseNotes = releaseNotes,
                    apk = AppReleaseAsset(
                        apkName,
                        apkDownloadUrl,
                        apkSizeBytes,
                        apkSha256Digest,
                    ),
                    checksum = AppReleaseAsset(
                        checksumName,
                        checksumDownloadUrl,
                        checksumSizeBytes,
                        checksumSha256Digest,
                    ),
                ),
            )
        }

        companion object {
            fun from(value: CachedAppRelease): StoredUpdateCheck = StoredUpdateCheck(
                schema = CURRENT_SCHEMA,
                checkedAtEpochMillis = value.checkedAtEpochMillis,
                tagName = value.release.tagName,
                major = value.release.version.major,
                minor = value.release.version.minor,
                patch = value.release.version.patch,
                releasePageUrl = value.release.releasePageUrl,
                releaseNotes = value.release.releaseNotes,
                apkName = value.release.apk.name,
                apkDownloadUrl = value.release.apk.downloadUrl,
                apkSizeBytes = value.release.apk.sizeBytes,
                apkSha256Digest = value.release.apk.sha256Digest,
                checksumName = value.release.checksum.name,
                checksumDownloadUrl = value.release.checksum.downloadUrl,
                checksumSizeBytes = value.release.checksum.sizeBytes,
                checksumSha256Digest = value.release.checksum.sha256Digest,
            )
        }
    }

    private companion object {
        const val CURRENT_SCHEMA = 1
        val LATEST_RELEASE = stringPreferencesKey("latest_release")
    }
}
