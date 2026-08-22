package app.opah.tv

import android.content.Context
import app.opah.tv.data.FrigateJsonParsers
import app.opah.tv.data.FrigateRepository
import app.opah.tv.data.CameraImageRepository
import app.opah.tv.data.FrigateSessionManager
import app.opah.tv.data.ProfileRepository
import app.opah.tv.data.ReviewImageRepository
import app.opah.tv.data.SettingsRepository
import app.opah.tv.data.network.FrigateApiClient
import app.opah.tv.data.network.FrigatePtzWebSocketClient
import app.opah.tv.data.network.PersistentCookieJar
import app.opah.tv.data.security.SecureSessionStore
import app.opah.tv.data.update.AppUpdateRepository
import app.opah.tv.data.update.DataStoreUpdateCheckCache
import app.opah.tv.data.update.GitHubReleaseApiClient
import app.opah.tv.data.update.AppUpdateDownloadClient
import app.opah.tv.data.update.AppUpdatePackageVerifier
import app.opah.tv.data.update.AppUpdatePreparationRepository
import app.opah.tv.device.DeviceMediaCapabilityService
import app.opah.tv.diagnostics.AndroidOpahLogger
import app.opah.tv.domain.StreamSelectionService
import app.opah.tv.playback.Media3LivePlayerFactory

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val secureSessionStore = SecureSessionStore(appContext)

    val cookieJar = PersistentCookieJar(secureSessionStore)
    val httpClient = FrigateApiClient.defaultClient(cookieJar)
    val apiClient = FrigateApiClient(httpClient, cookieJar)
    val ptzWebSocketClient = FrigatePtzWebSocketClient(httpClient)
    val profileRepository = ProfileRepository(appContext)
    val sessionManager = FrigateSessionManager(
        gateway = apiClient,
        cookieStore = cookieJar,
        credentialStore = secureSessionStore,
        profileStore = profileRepository,
    )
    val frigateRepository = FrigateRepository(apiClient, FrigateJsonParsers())
    val settingsRepository = SettingsRepository(appContext)
    private val updateApiClient = GitHubReleaseApiClient(
        httpClient = GitHubReleaseApiClient.defaultClient(),
        appVersionName = BuildConfig.VERSION_NAME,
    )
    val appUpdateRepository = AppUpdateRepository(
        gateway = updateApiClient,
        cache = DataStoreUpdateCheckCache(appContext),
        installedVersionName = BuildConfig.VERSION_NAME,
    )
    val appUpdatePreparationRepository = AppUpdatePreparationRepository(
        downloadClient = AppUpdateDownloadClient(GitHubReleaseApiClient.downloadClient()),
        packageVerifier = AppUpdatePackageVerifier(appContext),
        updateDirectory = java.io.File(appContext.cacheDir, "updates"),
    )
    val cameraImageRepository = CameraImageRepository(httpClient)
    val reviewImageRepository = ReviewImageRepository(httpClient)
    val logger = AndroidOpahLogger()
    val deviceMediaCapabilityService = DeviceMediaCapabilityService()
    val streamSelectionService = StreamSelectionService()
    val livePlayerFactory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Media3LivePlayerFactory()
    }
}
