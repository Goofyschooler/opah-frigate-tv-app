package app.opah.tv.notifications.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.graphics.scale
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.reviewThumbnailUrl
import app.opah.tv.data.reviewFallbackThumbnailUrl
import app.opah.tv.notifications.AlertThumbnailRequest
import app.opah.tv.notifications.NotificationPrivacy
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Memory-only, bounded notification image preparation and local redaction. */
internal class AndroidNotificationThumbnailStore(
    client: OkHttpClient,
    private val profile: suspend (String) -> ConnectionProfile?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val client = client.newBuilder()
        .callTimeout(THUMBNAIL_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(THUMBNAIL_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val cache = object : LruCache<String, PreparedThumbnail>(MAX_CACHE_KIB) {
        override fun sizeOf(key: String, value: PreparedThumbnail): Int =
            (value.bitmap.allocationByteCount / 1_024).coerceAtLeast(1)
    }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val requestLimit = Semaphore(MAX_CONCURRENT_REQUESTS)

    suspend fun prepare(request: AlertThumbnailRequest): String? {
        val keyPrefix = cachePrefix(request.profileKey, request.reviewId)
        val profile = profile(request.profileKey) ?: return null
        val urls = listOfNotNull(
            reviewThumbnailUrl(profile, request.thumbnailPath),
            reviewFallbackThumbnailUrl(profile, request.reviewId, request.cameraId),
        ).distinct()
        if (urls.isEmpty()) return null
        val sourceKey = "$keyPrefix|${request.privacy.name}|${urls.joinToString { it.encodedPath }}"
        return locks.computeIfAbsent(sourceKey) { Mutex() }.withLock {
            synchronized(cache) {
                cache.snapshot().values.firstOrNull { it.sourceKey == sourceKey }?.version
            } ?: requestLimit.withPermit {
                withContext(ioDispatcher) {
                    runCatching {
                        val bytes = urls.firstNotNullOfOrNull { fetchWithReadinessRetry(it) }
                            ?: return@runCatching null
                        val decoded = decodeBounded(bytes) ?: return@runCatching null
                        val prepared = if (request.privacy == NotificationPrivacy.BLURRED_PREVIEW) {
                            locallyBlurred(decoded)
                        } else {
                            decoded
                        }
                        val version = contentVersion(bytes, request.privacy)
                        val entry = PreparedThumbnail(sourceKey, version, prepared)
                        synchronized(cache) { cache.put("$keyPrefix|$version", entry) }
                        version
                    }.getOrNull()
                }
            }
        }
    }

    private suspend fun fetchWithReadinessRetry(url: okhttp3.HttpUrl): ByteArray? {
        for (attemptIndex in THUMBNAIL_RETRY_DELAYS_MILLIS.indices) {
            val attempt = fetchOnce(url)
            attempt.bytes?.let { return it }
            if (!attempt.retryable) return null
            val delayMillis = THUMBNAIL_RETRY_DELAYS_MILLIS[attemptIndex]
            if (delayMillis > 0L) delay(delayMillis)
        }
        return fetchOnce(url).bytes
    }

    private fun fetchOnce(url: okhttp3.HttpUrl): ThumbnailFetchAttempt = runCatching {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                return@use ThumbnailFetchAttempt(
                    retryable = response.code == 404 || response.code == 425 || response.code >= 500,
                )
            }
            val declared = response.body.contentLength()
            if (declared > MAX_IMAGE_BYTES) return@use ThumbnailFetchAttempt()
            ThumbnailFetchAttempt(bytes = response.body.byteStream().use(::readBounded))
        }
    }.getOrDefault(ThumbnailFetchAttempt())

    fun bitmap(profileKey: String, reviewId: String, version: String): Bitmap? =
        synchronized(cache) { cache.get("${cachePrefix(profileKey, reviewId)}|$version")?.bitmap }

    fun purge(profileKey: String, reviewId: String): Boolean = runCatching {
        val prefix = cachePrefix(profileKey, reviewId)
        synchronized(cache) {
            cache.snapshot().keys.filter { it.startsWith("$prefix|") }.forEach(cache::remove)
        }
        locks.keys.removeAll { it.startsWith("$prefix|") }
        true
    }.getOrDefault(false)

    fun purgeProfile(profileKey: String): Boolean = runCatching {
        synchronized(cache) {
            cache.snapshot().keys.filter { it.startsWith("$profileKey|") }.forEach(cache::remove)
        }
        locks.keys.removeAll { it.startsWith("$profileKey|") }
        true
    }.getOrDefault(false)

    fun clear() {
        synchronized(cache) { cache.evictAll() }
        locks.clear()
    }

    private fun readBounded(input: java.io.InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1_024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_IMAGE_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray().takeIf(ByteArray::isNotEmpty)
    }

    private fun decodeBounded(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!safeDimensions(bounds.outWidth, bounds.outHeight)) return null
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MAX_OUTPUT_WIDTH * 2 ||
            bounds.outHeight / sampleSize > MAX_OUTPUT_HEIGHT * 2
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null
        val scale = minOf(
            1f,
            MAX_OUTPUT_WIDTH.toFloat() / decoded.width,
            MAX_OUTPUT_HEIGHT.toFloat() / decoded.height,
        )
        if (scale >= 1f) return decoded.apply(Bitmap::prepareToDraw)
        return decoded.scale(
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
        ).also { scaled ->
            if (scaled !== decoded) decoded.recycle()
            scaled.prepareToDraw()
        }
    }

    /** A two-stage filtered reduction makes identities unreadable without retaining source pixels. */
    private fun locallyBlurred(source: Bitmap): Bitmap {
        val smallWidth = minOf(BLUR_WIDTH, source.width).coerceAtLeast(1)
        val smallHeight = (source.height.toLong() * smallWidth / source.width)
            .toInt().coerceIn(1, BLUR_HEIGHT)
        val reduced = source.scale(smallWidth, smallHeight, filter = true)
        val blurred = reduced.scale(source.width, source.height, filter = true)
        if (reduced !== source && reduced !== blurred) reduced.recycle()
        if (source !== blurred) source.recycle()
        blurred.prepareToDraw()
        return blurred
    }

    private fun contentVersion(bytes: ByteArray, privacy: NotificationPrivacy): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CONTENT_VERSION.toByteArray(StandardCharsets.UTF_8))
        digest.update(privacy.name.toByteArray(StandardCharsets.UTF_8))
        digest.update(bytes)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun cachePrefix(profileKey: String, reviewId: String): String = "$profileKey|$reviewId"

    private data class PreparedThumbnail(
        val sourceKey: String,
        val version: String,
        val bitmap: Bitmap,
    )

    private data class ThumbnailFetchAttempt(
        val bytes: ByteArray? = null,
        val retryable: Boolean = false,
    )

    private companion object {
        const val CONTENT_VERSION = "opah-notification-thumbnail-v1"
        const val MAX_IMAGE_BYTES = 1_500_000
        const val MAX_SOURCE_DIMENSION = 16_384
        const val MAX_SOURCE_PIXELS = 64_000_000L
        const val MAX_OUTPUT_WIDTH = 640
        const val MAX_OUTPUT_HEIGHT = 360
        const val BLUR_WIDTH = 32
        const val BLUR_HEIGHT = 32
        const val MAX_CACHE_KIB = 8 * 1_024
        const val MAX_CONCURRENT_REQUESTS = 2
        const val THUMBNAIL_CALL_TIMEOUT_SECONDS = 4L
        const val THUMBNAIL_READ_TIMEOUT_SECONDS = 3L
        val THUMBNAIL_RETRY_DELAYS_MILLIS = longArrayOf(250L, 750L)
    }
}

internal fun safeDimensions(width: Int, height: Int): Boolean =
    width in 1..16_384 &&
        height in 1..16_384 &&
        width.toLong() * height.toLong() <= 64_000_000L
