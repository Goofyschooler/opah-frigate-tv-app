package app.opah.tv.data.update

import java.io.File

class AppUpdatePreparationRepository(
    private val downloadClient: AppUpdateDownloadClient,
    private val packageVerifier: AppUpdatePackageVerifier,
    private val updateDirectory: File,
) {
    suspend fun prepare(release: AppRelease): PreparedAppUpdate {
        val apk = downloadClient.downloadAndVerify(release, updateDirectory)
        return try {
            packageVerifier.verify(apk, release.version)
            PreparedAppUpdate(apk.absolutePath, release.version)
        } catch (error: Throwable) {
            apk.delete()
            throw error
        }
    }
}
