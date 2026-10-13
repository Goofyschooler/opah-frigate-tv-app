package app.opah.tv.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.graphics.scale
import app.opah.tv.data.model.ConnectionProfile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class CameraImage(
    val bitmap: Bitmap,
    val loadedAtMillis: Long,
    val requestedHeight: Int = bitmap.height,
)

class CameraImageRepository(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cache = object : LruCache<String, CameraImage>(MAX_CACHE_KIB) {
        override fun sizeOf(key: String, value: CameraImage): Int =
            (value.bitmap.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val requestLimit = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val requestLocks = ConcurrentHashMap<String, Mutex>()
    private var cacheGeneration = 0L

    fun cached(profile: ConnectionProfile, cameraName: String): CameraImage? =
        synchronized(cache) { cache.get(cacheKey(profile, cameraName)) }

    suspend fun refresh(
        profile: ConnectionProfile,
        cameraName: String,
        height: Int = DEFAULT_HEIGHT,
        force: Boolean = false,
    ): Result<CameraImage> = runCatching {
        require(height in 120..1080) { "Camera image height is out of range." }
        val key = cacheKey(profile, cameraName)
        val generation = synchronized(cache) { cacheGeneration }
        requestLocks.computeIfAbsent(key) { Mutex() }.withLock {
            checkGeneration(generation)
            cached(profile, cameraName)?.takeIf { image ->
                val age = System.currentTimeMillis() - image.loadedAtMillis
                !force &&
                    age in 0..MIN_REFRESH_INTERVAL_MS &&
                    cameraImageRequestCanReuse(image.requestedHeight, height)
            } ?: requestLimit.withPermit {
                checkGeneration(generation)
                withContext(ioDispatcher) {
                    val url = profile.apiBaseUrl.toHttpUrl().newBuilder()
                        .addPathSegment("api")
                        .addPathSegment(cameraName)
                        .addPathSegment("latest.jpg")
                        .addQueryParameter("h", height.toString())
                        .build()
                    val bytes = client.newCall(Request.Builder().url(url).get().build()).awaitCameraImageBytes(MAX_IMAGE_BYTES)
                    currentCoroutineContext().ensureActive()
                    checkGeneration(generation)
                    val bitmap = decodeCameraBitmap(bytes, height)
                        ?: error("Frigate returned an invalid camera image")
                    try {
                        currentCoroutineContext().ensureActive()
                        CameraImage(bitmap, System.currentTimeMillis(), requestedHeight = height).also { image ->
                            synchronized(cache) {
                                // Privacy/profile changes clear the cache even while an HTTP response is in flight.
                                checkGeneration(generation)
                                cache.put(key, image)
                            }
                        }
                    } catch (error: Throwable) {
                        bitmap.recycle()
                        throw error
                    }
                }
            }
        }
    }.onFailure { failure -> if (failure is CancellationException) throw failure }

    fun clear() {
        synchronized(cache) {
            cacheGeneration += 1
            cache.evictAll()
        }
    }

    private fun checkGeneration(generation: Long) {
        synchronized(cache) {
            // An obsolete response ends this fetch, not the caller's ongoing visible-preview loop.
            if (cacheGeneration != generation) throw ObsoleteCameraImageRequestException()
        }
    }

    private fun cacheKey(profile: ConnectionProfile, cameraName: String): String =
        "${profile.apiBaseUrl}|$cameraName"

    private fun decodeCameraBitmap(bytes: ByteArray, targetHeight: Int): Bitmap? {
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
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        const val MAX_CACHE_KIB = 32 * 1024
        const val MAX_CONCURRENT_REQUESTS = 3
        const val MIN_REFRESH_INTERVAL_MS = 5_000L
    }
}

internal class ObsoleteCameraImageRequestException : IllegalStateException("Camera preview request expired")

internal fun cameraImageRequestCanReuse(cachedRequestHeight: Int, targetHeight: Int): Boolean =
    cachedRequestHeight >= targetHeight

internal fun cameraImageSampleSize(sourceHeight: Int, targetHeight: Int): Int {
    if (sourceHeight <= 0 || targetHeight <= 0) return 1
    var sampleSize = 1
    while (sourceHeight / (sampleSize * 2) >= targetHeight) sampleSize *= 2
    return sampleSize
}

internal fun cameraImageScaledWidth(sourceWidth: Int, sourceHeight: Int, targetHeight: Int): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetHeight <= 0) return 1
    return ((sourceWidth.toLong() * targetHeight) / sourceHeight).toInt().coerceAtLeast(1)
}
