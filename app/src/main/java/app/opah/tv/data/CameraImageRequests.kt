package app.opah.tv.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Cancellation stays attached until the response body has been consumed and closed. */
internal suspend fun Call.awaitCameraImageBytes(maxBytes: Int): ByteArray = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching {
                response.use {
                    continuation.context.ensureActive()
                    check(response.isSuccessful) { "Camera image request failed" }
                    val declaredLength = response.body.contentLength()
                    check(declaredLength <= maxBytes || declaredLength == -1L) { "Camera image is too large" }
                    response.body.byteStream().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            continuation.context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= maxBytes) { "Camera image is too large" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
            }
            if (continuation.isActive) {
                result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
        }
    })
}
