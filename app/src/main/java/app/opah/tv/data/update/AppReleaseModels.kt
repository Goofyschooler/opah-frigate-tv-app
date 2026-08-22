package app.opah.tv.data.update

data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AppVersion> {
    val canonical: String get() = "$major.$minor.$patch"

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    companion object {
        private val VERSION_PATTERN = Regex(
            """^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:[-+][0-9A-Za-z.-]+)?$""",
        )

        /** Build suffixes are ignored because GitHub update releases use stable X.Y.Z tags. */
        fun parse(value: String): AppVersion? {
            val match = VERSION_PATTERN.matchEntire(value.trim()) ?: return null
            return AppVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
            )
        }
    }
}

data class AppReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** Lowercase hexadecimal SHA-256 reported by GitHub, when GitHub provides one. */
    val sha256Digest: String? = null,
)

data class AppRelease(
    val tagName: String,
    val version: AppVersion,
    val releasePageUrl: String,
    val apk: AppReleaseAsset,
    val checksum: AppReleaseAsset,
    val releaseNotes: String = "",
)

enum class AppUpdateAvailability {
    UP_TO_DATE,
    UPDATE_AVAILABLE,
}

enum class UpdateCheckSource {
    NETWORK,
    CACHE,
    STALE_CACHE,
}

data class AppUpdateCheckResult(
    val availability: AppUpdateAvailability,
    val latestRelease: AppRelease,
    val checkedAtEpochMillis: Long,
    val source: UpdateCheckSource,
) {
    val availableRelease: AppRelease?
        get() = latestRelease.takeIf { availability == AppUpdateAvailability.UPDATE_AVAILABLE }
}

data class CachedAppRelease(
    val checkedAtEpochMillis: Long,
    val release: AppRelease,
)

interface AppReleaseGateway {
    suspend fun latestRelease(): AppRelease
}

interface UpdateCheckCache {
    suspend fun read(): CachedAppRelease?
    suspend fun write(value: CachedAppRelease)
}

enum class AppUpdateErrorCode {
    CONNECTION,
    RATE_LIMITED,
    NO_RELEASE,
    INVALID_RESPONSE,
    INVALID_INSTALLED_VERSION,
    DOWNLOAD_FAILED,
    VERIFICATION_FAILED,
}

data class PreparedAppUpdate(
    val apkPath: String,
    val version: AppVersion,
)

class AppUpdateException(
    val code: AppUpdateErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
