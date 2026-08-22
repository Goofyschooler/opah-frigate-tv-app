package app.opah.tv.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Reads only the latest published release from Opah's public GitHub repository. */
class GitHubReleaseApiClient(
    private val httpClient: OkHttpClient,
    private val appVersionName: String,
    private val repositoryOwner: String = OPAH_REPOSITORY_OWNER,
    private val repositoryName: String = OPAH_REPOSITORY_NAME,
    apiBaseUrl: HttpUrl = GITHUB_API_BASE_URL,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AppReleaseGateway {
    private val latestReleaseUrl = apiBaseUrl.newBuilder()
        .addPathSegment("repos")
        .addPathSegment(repositoryOwner)
        .addPathSegment(repositoryName)
        .addPathSegment("releases")
        .addPathSegment("latest")
        .build()

    override suspend fun latestRelease(): AppRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(latestReleaseUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .header("User-Agent", "Opah-Android-TV/$appVersionName")
            .get()
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw responseFailure(response.code)
                parseRelease(response.body.string())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppUpdateException) {
            throw error
        } catch (error: IOException) {
            throw AppUpdateException(
                AppUpdateErrorCode.CONNECTION,
                "Opah couldn't check for an update. Try again later.",
                error,
            )
        } catch (error: Throwable) {
            throw invalidResponse(error)
        }
    }

    internal fun parseRelease(rawJson: String): AppRelease {
        val root = try {
            json.parseToJsonElement(rawJson) as? JsonObject
        } catch (error: Throwable) {
            throw invalidResponse(error)
        } ?: throw invalidResponse()

        if (root.bool("draft") != false || root.bool("prerelease") != false) {
            throw invalidResponse()
        }
        val tagName = root.string("tag_name") ?: throw invalidResponse()
        val version = AppVersion.parse(tagName) ?: throw invalidResponse()
        if (tagName != "v${version.canonical}") throw invalidResponse()

        val releasePageUrl = root.string("html_url") ?: throw invalidResponse()
        validateReleasePageUrl(releasePageUrl, tagName)
        val releaseNotes = root.string("body")
            ?.trim()
            ?.take(MAX_RELEASE_NOTES_LENGTH)
            .orEmpty()

        val rawAssets = root["assets"] as? JsonArray ?: throw invalidResponse()
        val assets = rawAssets.map { element ->
            val asset = element as? JsonObject ?: throw invalidResponse()
            val name = asset.string("name") ?: throw invalidResponse()
            val url = asset.string("browser_download_url") ?: throw invalidResponse()
            val size = asset.long("size") ?: throw invalidResponse()
            RawAsset(name, url, size, parseDigest(asset.string("digest")))
        }
        val expectedApkName = "opah-$tagName.apk"
        val expectedChecksumName = "$expectedApkName.sha256"
        val requiredAssetNames = setOf(expectedApkName, expectedChecksumName)
        val assetNames = assets.map(RawAsset::name)
        val acceptedAssetNames = setOf(requiredAssetNames, requiredAssetNames + LATEST_APK_ALIAS)
        if (
            assetNames.toSet().size != assets.size ||
            assetNames.toSet() !in acceptedAssetNames
        ) {
            throw invalidResponse()
        }
        val apk = assets.singleOrNull { it.name == expectedApkName } ?: throw invalidResponse()
        val checksum = assets.singleOrNull { it.name == expectedChecksumName }
            ?: throw invalidResponse()
        val latestAlias = assets.singleOrNull { it.name == LATEST_APK_ALIAS }
        if (
            apk.sizeBytes <= 0L || checksum.sizeBytes <= 0L ||
            (latestAlias != null && (
                latestAlias.sizeBytes <= 0L ||
                    latestAlias.sizeBytes != apk.sizeBytes ||
                    (apk.sha256Digest != null && latestAlias.sha256Digest != null &&
                        apk.sha256Digest != latestAlias.sha256Digest)
                ))
        ) throw invalidResponse()

        validateDownloadUrl(apk.downloadUrl, tagName, apk.name)
        validateDownloadUrl(checksum.downloadUrl, tagName, checksum.name)
        latestAlias?.let { validateDownloadUrl(it.downloadUrl, tagName, it.name) }
        return AppRelease(
            tagName = tagName,
            version = version,
            releasePageUrl = releasePageUrl,
            apk = apk.toModel(),
            checksum = checksum.toModel(),
            releaseNotes = releaseNotes,
        )
    }

    private fun validateReleasePageUrl(value: String, tagName: String) {
        val url = value.toHttpUrlOrNull() ?: throw invalidResponse()
        val expected = listOf(repositoryOwner, repositoryName, "releases", "tag", tagName)
        if (!url.isHttps || url.host != GITHUB_WEB_HOST || url.pathSegments != expected) {
            throw invalidResponse()
        }
    }

    private fun validateDownloadUrl(value: String, tagName: String, assetName: String) {
        val url = value.toHttpUrlOrNull() ?: throw invalidResponse()
        val expected = listOf(
            repositoryOwner,
            repositoryName,
            "releases",
            "download",
            tagName,
            assetName,
        )
        if (!url.isHttps || url.host != GITHUB_WEB_HOST || url.pathSegments != expected) {
            throw invalidResponse()
        }
    }

    private fun parseDigest(value: String?): String? {
        if (value == null) return null
        val match = SHA256_DIGEST.matchEntire(value) ?: throw invalidResponse()
        return match.groupValues[1].lowercase()
    }

    private fun responseFailure(statusCode: Int): AppUpdateException = when (statusCode) {
        403, 429 -> AppUpdateException(
            AppUpdateErrorCode.RATE_LIMITED,
            "GitHub is receiving too many update checks. Try again later.",
        )
        404 -> AppUpdateException(
            AppUpdateErrorCode.NO_RELEASE,
            "No Opah update is available from GitHub right now.",
        )
        else -> invalidResponse()
    }

    private fun invalidResponse(cause: Throwable? = null): AppUpdateException = AppUpdateException(
        AppUpdateErrorCode.INVALID_RESPONSE,
        "GitHub returned update information Opah couldn't use.",
        cause,
    )

    private data class RawAsset(
        val name: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val sha256Digest: String?,
    ) {
        fun toModel(): AppReleaseAsset = AppReleaseAsset(
            name = name,
            downloadUrl = downloadUrl,
            sizeBytes = sizeBytes,
            sha256Digest = sha256Digest,
        )
    }

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = get(key)?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.long(key: String): Long? = get(key)?.jsonPrimitive?.longOrNull

    companion object {
        const val OPAH_REPOSITORY_OWNER = "VibeCodingAntagonist"
        const val OPAH_REPOSITORY_NAME = "opah-frigate-tv-app"
        const val LATEST_APK_ALIAS = "opah-latest.apk"
        private const val GITHUB_WEB_HOST = "github.com"
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val MAX_RELEASE_NOTES_LENGTH = 8_000
        private val GITHUB_API_BASE_URL = "https://api.github.com".toHttpUrl()
        private val SHA256_DIGEST = Regex("^sha256:([0-9a-fA-F]{64})$")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        fun downloadClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }
}
