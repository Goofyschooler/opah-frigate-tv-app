package app.opah.tv.data.update

import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Applies the once-daily update policy without downloading or installing anything. */
class AppUpdateRepository(
    private val gateway: AppReleaseGateway,
    private val cache: UpdateCheckCache,
    private val installedVersionName: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshIntervalMillis: Long = TimeUnit.HOURS.toMillis(24),
) {
    init {
        require(refreshIntervalMillis > 0L) { "Update refresh interval must be positive." }
    }

    suspend fun check(forceRefresh: Boolean = false): AppUpdateCheckResult {
        val installedVersion = AppVersion.parse(installedVersionName) ?: throw AppUpdateException(
            AppUpdateErrorCode.INVALID_INSTALLED_VERSION,
            "Opah couldn't compare the installed version with the latest release.",
        )
        val now = clock()
        val cached = cache.read()
        if (!forceRefresh && cached != null && cached.isFreshAt(now)) {
            return evaluate(installedVersion, cached, UpdateCheckSource.CACHE)
        }

        return try {
            val latest = gateway.latestRelease()
            val fresh = CachedAppRelease(now, latest)
            try {
                cache.write(fresh)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // A cache write must not turn a successful GitHub check into a user-facing error.
            }
            evaluate(installedVersion, fresh, UpdateCheckSource.NETWORK)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (!forceRefresh && cached != null) {
                evaluate(installedVersion, cached, UpdateCheckSource.STALE_CACHE)
            } else {
                throw error
            }
        }
    }

    private fun CachedAppRelease.isFreshAt(now: Long): Boolean {
        val age = now - checkedAtEpochMillis
        return age in 0 until refreshIntervalMillis
    }

    private fun evaluate(
        installedVersion: AppVersion,
        cached: CachedAppRelease,
        source: UpdateCheckSource,
    ): AppUpdateCheckResult = AppUpdateCheckResult(
        availability = if (cached.release.version > installedVersion) {
            AppUpdateAvailability.UPDATE_AVAILABLE
        } else {
            AppUpdateAvailability.UP_TO_DATE
        },
        latestRelease = cached.release,
        checkedAtEpochMillis = cached.checkedAtEpochMillis,
        source = source,
    )
}
