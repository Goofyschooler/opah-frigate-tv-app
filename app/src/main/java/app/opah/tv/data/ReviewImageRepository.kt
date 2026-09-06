package app.opah.tv.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.graphics.scale
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.RecordingExport
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.SearchEvent
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class ReviewImage(
    val bitmap: Bitmap,
    val loadedAtMillis: Long,
)

class ReviewImageRepository(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cache = object : LruCache<String, ReviewImage>(MAX_CACHE_KIB) {
        override fun sizeOf(key: String, value: ReviewImage): Int =
            (value.bitmap.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val requestLimit = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val requestLocks = ConcurrentHashMap<String, Mutex>()
    @Volatile
    private var cacheGeneration = 0L

    fun cached(profile: ConnectionProfile, item: ReviewItem): ReviewImage? =
        synchronized(cache) { cache.get(cacheKey(profile, item)) }

    fun cached(profile: ConnectionProfile, event: SearchEvent): ReviewImage? =
        synchronized(cache) { cache.get(cacheKey(profile, event)) }

    fun cached(profile: ConnectionProfile, export: RecordingExport): ReviewImage? =
        synchronized(cache) { cache.get(cacheKey(profile, export)) }

    suspend fun refresh(
        profile: ConnectionProfile,
        item: ReviewItem,
        height: Int = DEFAULT_HEIGHT,
    ): Result<ReviewImage> {
        val urls = listOfNotNull(
            reviewThumbnailUrl(profile, item.thumbnailPath),
            reviewFallbackThumbnailUrl(profile, item.id, item.camera),
        ).distinct()
        if (urls.isEmpty()) {
            return Result.failure(IllegalArgumentException("This Review item has no safe thumbnail"))
        }
        var lastFailure: Throwable? = null
        urls.forEach { url ->
            val result = refresh(cacheKey(profile, item), url, height)
            result.getOrNull()?.let { return Result.success(it) }
            lastFailure = result.exceptionOrNull()
        }
        return Result.failure(lastFailure ?: IllegalStateException("Review thumbnail request failed"))
    }

    suspend fun refresh(
        profile: ConnectionProfile,
        event: SearchEvent,
        height: Int = DEFAULT_HEIGHT,
    ): Result<ReviewImage> = refresh(
        key = cacheKey(profile, event),
        url = eventThumbnailUrl(profile, event.id),
        height = height,
    )

    suspend fun refresh(
        profile: ConnectionProfile,
        export: RecordingExport,
        height: Int = DEFAULT_HEIGHT,
    ): Result<ReviewImage> {
        val url = exportThumbnailUrl(profile, export.thumbnailPath)
            ?: return Result.failure(IllegalArgumentException("This saved recording has no safe preview"))
        return refresh(cacheKey(profile, export), url, height)
    }

    private suspend fun refresh(
        key: String,
        url: HttpUrl,
        height: Int,
    ): Result<ReviewImage> = runCatching {
        val refreshGeneration = cacheGeneration
        require(height in 120..1080) { "Review image height is out of range." }
        requestLocks.computeIfAbsent(key) { Mutex() }.withLock {
            synchronized(cache) { cache.get(key) }?.takeIf { it.bitmap.height >= height }
                ?: requestLimit.withPermit {
                withContext(ioDispatcher) {
                    client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                        check(response.isSuccessful) { "Review thumbnail request failed." }
                        val declaredLength = response.body.contentLength()
                        check(declaredLength <= MAX_IMAGE_BYTES || declaredLength == -1L) {
                            "Review thumbnail is too large."
                        }
                        val bytes = response.body.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= MAX_IMAGE_BYTES) { "Review thumbnail is too large." }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        val bitmap = decodeReviewBitmap(bytes, height)
                            ?: error("Frigate returned an invalid Review thumbnail")
                        ReviewImage(bitmap, System.currentTimeMillis()).also { image ->
                            synchronized(cache) {
                                if (cacheGeneration == refreshGeneration) cache.put(key, image)
                            }
                        }
                    }
                }
            }
        }
    }

    fun clear() {
        synchronized(cache) {
            cacheGeneration++
            cache.evictAll()
        }
    }

    private fun cacheKey(profile: ConnectionProfile, item: ReviewItem): String =
        "${profile.apiBaseUrl}|review|${item.id}|${item.thumbnailPath.orEmpty()}"

    private fun cacheKey(profile: ConnectionProfile, event: SearchEvent): String =
        "${profile.apiBaseUrl}|event|${event.id}"

    private fun cacheKey(profile: ConnectionProfile, export: RecordingExport): String =
        "${profile.apiBaseUrl}|export|${export.id}|${export.thumbnailPath.orEmpty()}"

    private fun decodeReviewBitmap(bytes: ByteArray, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = cameraImageSampleSize(bounds.outHeight, targetHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val output = if (decoded.height > targetHeight) {
            val targetWidth = cameraImageScaledWidth(decoded.width, decoded.height, targetHeight)
            decoded.scale(targetWidth, targetHeight).also { scaled ->
                if (scaled !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }
        output.prepareToDraw()
        return output
    }

    private companion object {
        const val DEFAULT_HEIGHT = 360
        const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
        const val MAX_CACHE_KIB = 32 * 1024
        const val MAX_CONCURRENT_REQUESTS = 3
    }
}

internal fun eventThumbnailUrl(profile: ConnectionProfile, eventId: String): HttpUrl {
    require(eventId.isNotBlank()) { "Event ID is required." }
    return profile.apiBaseUrl.toHttpUrl().newBuilder()
        .addPathSegments("api/events")
        .addPathSegment(eventId)
        .addPathSegment("thumbnail.jpg")
        .build()
}

internal fun reviewThumbnailUrl(
    profile: ConnectionProfile,
    thumbnailPath: String?,
): HttpUrl? {
    val relative = when {
        thumbnailPath?.startsWith(REVIEW_MEDIA_PREFIX) == true ->
            thumbnailPath.removePrefix(REVIEW_MEDIA_PREFIX)
        thumbnailPath?.startsWith(REVIEW_DIRECT_PREFIX) == true ->
            thumbnailPath.removePrefix(REVIEW_DIRECT_PREFIX)
        else -> return null
    }
    val segments = relative.split('/').filter(String::isNotBlank)
    if (segments.isEmpty() || segments.any { it == "." || it == ".." || '\\' in it }) return null
    return profile.apiBaseUrl.toHttpUrl().newBuilder()
        .addPathSegments("clips/review")
        .apply { segments.forEach { addPathSegment(it) } }
        .build()
}

internal fun reviewFallbackThumbnailUrl(
    profile: ConnectionProfile,
    reviewId: String,
    cameraId: String,
): HttpUrl? {
    if (!reviewId.isSafeReviewThumbnailIdentifier() || !cameraId.isSafeReviewThumbnailIdentifier()) {
        return null
    }
    return profile.apiBaseUrl.toHttpUrl().newBuilder()
        .addPathSegments("clips/review")
        .addPathSegment("thumb-$cameraId-$reviewId.webp")
        .build()
}

internal fun exportThumbnailUrl(
    profile: ConnectionProfile,
    thumbnailPath: String?,
): HttpUrl? {
    val relative = thumbnailPath
        ?.takeIf { it.startsWith(EXPORT_THUMBNAIL_MEDIA_PREFIX) }
        ?.removePrefix(EXPORT_THUMBNAIL_MEDIA_PREFIX)
        ?: return null
    val segments = relative.split('/').filter(String::isNotBlank)
    if (segments.isEmpty() || segments.any { it == "." || it == ".." || '\\' in it }) return null
    return profile.apiBaseUrl.toHttpUrl().newBuilder()
        .addPathSegments("clips/export")
        .apply { segments.forEach { addPathSegment(it) } }
        .build()
}

private const val REVIEW_MEDIA_PREFIX = "/media/frigate/clips/review/"
private const val REVIEW_DIRECT_PREFIX = "/clips/review/"
private const val EXPORT_THUMBNAIL_MEDIA_PREFIX = "/media/frigate/clips/export/"

private fun String.isSafeReviewThumbnailIdentifier(): Boolean =
    isNotBlank() && length <= 256 && none {
        it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
    }
