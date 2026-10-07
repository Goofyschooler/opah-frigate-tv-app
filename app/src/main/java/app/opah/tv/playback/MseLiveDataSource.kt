package app.opah.tv.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Frigate's authenticated MSE WebSocket supplies an fMP4 init segment then fragments. */
@UnstableApi
internal class MseLiveDataSource(private val client: OkHttpClient) : BaseDataSource(true) {
    private class Session {
        val pipe = LiveBytePipe()
        @Volatile var socket: WebSocket? = null
        @Volatile var cancelled = false
        fun cancel() {
            cancelled = true
            pipe.fail(IOException("Live stream closed"))
            socket?.cancel()
        }
    }

    @Volatile private var session: Session? = null
    @Volatile private var stopped = false
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        if (stopped) throw IOException("Live stream closed")
        if (dataSpec.position != 0L) throw IOException("Live stream cannot seek")
        val url = dataSpec.uri.toString().toHttpUrl()
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw IOException("Secure live origin required")
        }
        transferInitializing(dataSpec)
        val active = Session()
        session = active
        if (stopped) {
            active.cancel()
            throw IOException("Live stream closed")
        }
        uri = dataSpec.uri
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (active.cancelled || stopped) {
                    webSocket.cancel()
                    return
                }
                // H.264 + AAC only. Unsupported cameras fail, never trigger server transcoding edits.
                if (!webSocket.send("""{"type":"mse","value":"avc1.640029,mp4a.40.2,mp4a.40.5"}""")) {
                    active.cancel()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val type = if (text.length <= 16_384) runCatching {
                    Json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
                }.getOrNull() else null
                if (type == null || type == "error") {
                    active.pipe.fail(LiveTransportException())
                    webSocket.cancel()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > 8 * 1024 * 1024 || !active.pipe.offer(bytes.toByteArray())) {
                    active.pipe.fail(IOException("Live buffer limit reached"))
                    webSocket.cancel()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                active.pipe.fail(LiveTransportException(response?.code))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                active.pipe.fail(IOException("Live connection ended"))
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                active.pipe.fail(IOException("Live connection ended"))
            }
        })
        active.socket = socket
        if (active.cancelled || stopped) active.cancel()
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = (session ?: throw IOException("Live stream closed")).pipe.read(buffer, offset, length)
        if (count > 0) bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    /** Called before player.release so a waiting reader cannot delay UI teardown. */
    fun cancel() {
        stopped = true
        session?.cancel()
    }

    override fun close() {
        cancel()
        session = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
