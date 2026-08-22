package app.opah.tv.data.network

import app.opah.tv.data.model.ConnectionProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class PtzConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

data class PtzConnectionState(
    val status: PtzConnectionStatus = PtzConnectionStatus.DISCONNECTED,
    val message: String? = null,
)

sealed class PtzCommand(val payload: String) {
    data object MoveLeft : PtzCommand("MOVE_LEFT")
    data object MoveRight : PtzCommand("MOVE_RIGHT")
    data object MoveUp : PtzCommand("MOVE_UP")
    data object MoveDown : PtzCommand("MOVE_DOWN")
    data object ZoomIn : PtzCommand("ZOOM_IN")
    data object ZoomOut : PtzCommand("ZOOM_OUT")
    data object FocusIn : PtzCommand("FOCUS_IN")
    data object FocusOut : PtzCommand("FOCUS_OUT")
    data object Stop : PtzCommand("STOP")
    data class Preset(val name: String) : PtzCommand("preset_${name.trim()}") {
        init {
            require(name.isNotBlank()) { "Preset name is required." }
        }
    }
}

class FrigatePtzWebSocketClient(
    private val httpClient: OkHttpClient,
    private val json: Json = Json,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(PtzConnectionState())
    val state: StateFlow<PtzConnectionState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var connectedProfileUrl: String? = null
    private var intentionalClose = false

    fun connect(profile: ConnectionProfile) {
        val url = ptzWebSocketUrl(profile)
        synchronized(lock) {
            if (
                connectedProfileUrl == url &&
                _state.value.status in setOf(PtzConnectionStatus.CONNECTING, PtzConnectionStatus.CONNECTED)
            ) {
                return
            }
            closeLocked()
            intentionalClose = false
            connectedProfileUrl = url
            _state.value = PtzConnectionState(PtzConnectionStatus.CONNECTING)
            val request = Request.Builder().url(url).build()
            socket = httpClient.newWebSocket(request, Listener())
        }
    }

    fun send(camera: String, command: PtzCommand): Boolean {
        require(camera.isNotBlank()) { "Camera is required." }
        val message = ptzCommandMessage(json, camera, command)
        return synchronized(lock) {
            if (_state.value.status != PtzConnectionStatus.CONNECTED) return@synchronized false
            socket?.send(message) == true
        }
    }

    fun disconnect() {
        synchronized(lock) {
            intentionalClose = true
            closeLocked()
            _state.value = PtzConnectionState()
        }
    }

    private fun closeLocked() {
        socket?.close(NORMAL_CLOSE_CODE, "Controls closed")
        socket = null
        connectedProfileUrl = null
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (webSocket !== socket) return
                _state.value = PtzConnectionState(PtzConnectionStatus.CONNECTED)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                if (webSocket !== socket || intentionalClose) return
                socket = null
                connectedProfileUrl = null
                _state.value = PtzConnectionState(
                    PtzConnectionStatus.FAILED,
                    "Camera controls disconnected",
                )
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) {
                if (webSocket !== socket || intentionalClose) return
                socket = null
                connectedProfileUrl = null
                _state.value = PtzConnectionState(
                    PtzConnectionStatus.FAILED,
                    when (response?.code) {
                        401 -> "Sign in again to use camera controls"
                        403 -> "This account cannot control that camera"
                        else -> "Camera controls could not connect"
                    },
                )
            }
        }
    }

    private companion object {
        const val NORMAL_CLOSE_CODE = 1000
    }
}

internal fun ptzWebSocketUrl(profile: ConnectionProfile): String {
    val httpUrl = profile.apiBaseUrl.toHttpUrl().newBuilder()
        .addPathSegment("ws")
        .build()
    return if (httpUrl.isHttps) {
        httpUrl.toString().replaceFirst("https://", "wss://")
    } else {
        httpUrl.toString().replaceFirst("http://", "ws://")
    }
}

internal fun ptzCommandMessage(json: Json, camera: String, command: PtzCommand): String = buildString {
    append("{\"topic\":")
    append(json.encodeToString("$camera/ptz"))
    append(",\"payload\":")
    append(json.encodeToString(command.payload))
    append(",\"retain\":false}")
}
