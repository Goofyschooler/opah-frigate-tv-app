package app.opah.tv.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class AppUpdateDownloadClient(
    private val httpClient: OkHttpClient,
) {
    suspend fun downloadAndVerify(release: AppRelease, directory: File): File = withContext(Dispatchers.IO) {
        validateRelease(release)
        if (!directory.exists() && !directory.mkdirs()) throw downloadFailure()
        if (!directory.isDirectory) throw downloadFailure()
        directory.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".apk") || file.name.endsWith(".part"))) file.delete()
        }

        val checksumBytes = downloadBytes(release.checksum, CHECKSUM_MAX_BYTES)
        verifyOptionalGitHubDigest(release.checksum, sha256(checksumBytes))
        val expectedApkDigest = parseChecksum(checksumBytes, release.apk.name)
        val temporary = File(directory, "${release.apk.name}.part")
        val destination = File(directory, release.apk.name)
        try {
            val actualApkDigest = downloadFile(release.apk, temporary)
            if (actualApkDigest != expectedApkDigest) throw verificationFailure()
            verifyOptionalGitHubDigest(release.apk, actualApkDigest)
            if (!temporary.renameTo(destination)) throw downloadFailure()
            destination
        } catch (error: Throwable) {
            temporary.delete()
            if (error is CancellationException) throw error
            if (error is AppUpdateException) throw error
            throw downloadFailure(error)
        }
    }

    private fun downloadBytes(asset: AppReleaseAsset, maximumBytes: Long): ByteArray {
        if (asset.sizeBytes !in 1..maximumBytes) throw verificationFailure()
        return executeAsset(asset).use { response ->
            val body = response.body
            val contentLength = body.contentLength()
            if (contentLength >= 0 && contentLength != asset.sizeBytes) throw verificationFailure()
            val output = ByteArrayOutputStream(asset.sizeBytes.toInt())
            var total = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > asset.sizeBytes || total > maximumBytes) throw verificationFailure()
                    output.write(buffer, 0, read)
                }
            }
            if (total != asset.sizeBytes) throw verificationFailure()
            output.toByteArray()
        }
    }

    private fun downloadFile(asset: AppReleaseAsset, destination: File): String {
        if (asset.sizeBytes !in 1..APK_MAX_BYTES) throw verificationFailure()
        return executeAsset(asset).use { response ->
            val body = response.body
            val contentLength = body.contentLength()
            if (contentLength >= 0 && contentLength != asset.sizeBytes) throw verificationFailure()
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            destination.outputStream().buffered().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > asset.sizeBytes || total > APK_MAX_BYTES) throw verificationFailure()
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total != asset.sizeBytes) throw verificationFailure()
            digest.digest().toHex()
        }
    }

    private fun executeAsset(asset: AppReleaseAsset): okhttp3.Response {
        var url = asset.downloadUrl.toHttpUrlOrNull() ?: throw verificationFailure()
        repeat(MAX_REQUESTS) { requestIndex ->
            val response = try {
                httpClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Accept", "application/octet-stream")
                        .header("User-Agent", "Opah-Android-TV")
                        .get()
                        .build(),
                ).execute()
            } catch (error: IOException) {
                throw downloadFailure(error)
            }
            if (response.isSuccessful) return response
            if (response.code !in REDIRECT_CODES || requestIndex == MAX_REQUESTS - 1) {
                response.close()
                throw downloadFailure()
            }
            val location = response.header("Location")
            response.close()
            val redirected = location?.toHttpUrlOrNull() ?: throw verificationFailure()
            if (!redirected.isHttps || redirected.host !in ALLOWED_DOWNLOAD_HOSTS) {
                throw verificationFailure()
            }
            url = redirected
        }
        throw downloadFailure()
    }

    private fun validateRelease(release: AppRelease) {
        val tag = "v${release.version.canonical}"
        if (release.tagName != tag) throw verificationFailure()
        val expectedApk = "opah-$tag.apk"
        if (release.apk.name != expectedApk || release.checksum.name != "$expectedApk.sha256") {
            throw verificationFailure()
        }
        validateInitialUrl(release.apk, tag)
        validateInitialUrl(release.checksum, tag)
    }

    private fun validateInitialUrl(asset: AppReleaseAsset, tag: String) {
        val url = asset.downloadUrl.toHttpUrlOrNull() ?: throw verificationFailure()
        val expected = listOf(
            GitHubReleaseApiClient.OPAH_REPOSITORY_OWNER,
            GitHubReleaseApiClient.OPAH_REPOSITORY_NAME,
            "releases",
            "download",
            tag,
            asset.name,
        )
        if (!url.isHttps || url.host != "github.com" || url.pathSegments != expected || url.query != null) {
            throw verificationFailure()
        }
    }

    private fun parseChecksum(bytes: ByteArray, apkName: String): String {
        val text = runCatching { bytes.toString(Charsets.US_ASCII).trimEnd('\r', '\n') }
            .getOrElse { throw verificationFailure(it) }
        val match = Regex("^([0-9a-fA-F]{64})\\s+\\*?${Regex.escape(apkName)}$").matchEntire(text)
            ?: throw verificationFailure()
        return match.groupValues[1].lowercase()
    }

    private fun verifyOptionalGitHubDigest(asset: AppReleaseAsset, actual: String) {
        if (asset.sha256Digest != null && asset.sha256Digest != actual) throw verificationFailure()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun downloadFailure(cause: Throwable? = null) = AppUpdateException(
        AppUpdateErrorCode.DOWNLOAD_FAILED,
        "Opah couldn't download the update. Try again later.",
        cause,
    )

    private fun verificationFailure(cause: Throwable? = null) = AppUpdateException(
        AppUpdateErrorCode.VERIFICATION_FAILED,
        "The downloaded update could not be verified.",
        cause,
    )

    private companion object {
        const val CHECKSUM_MAX_BYTES = 4L * 1024L
        const val APK_MAX_BYTES = 250L * 1024L * 1024L
        const val MAX_REQUESTS = 3
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val ALLOWED_DOWNLOAD_HOSTS = setOf("release-assets.githubusercontent.com")
    }
}
