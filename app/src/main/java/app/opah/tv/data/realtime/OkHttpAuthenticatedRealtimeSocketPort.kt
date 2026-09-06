package app.opah.tv.data.realtime

import app.opah.tv.data.model.ConnectionProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

fun interface RealtimeConnectionProfileResolver {
    suspend fun resolve(profileId: String): ConnectionProfile?
}

/** Authenticated OkHttp socket adapter. The injected factory must use Opah's existing cookie jar. */
class OkHttpAuthenticatedRealtimeSocketPort(
    private val webSockets: WebSocket.Factory,
    private val scope: CoroutineScope,
    private val profiles: RealtimeConnectionProfileResolver,
    private val parser: RealtimeInboundMessageParser,
    private val events: RealtimeEventSink,
    private val currentState: () -> RealtimeTransportState,
    private val json: Json = Json,
) : AuthenticatedRealtimeSocketPort {
    private val lock = Any()
    private var pendingOpen: OpenSharedSocket? = null
    private var profileResolution: Job? = null
    private var active: ActiveSocket? = null
    private var lastPublishedPtz: Pair<Long, RealtimeOperationId>? = null

    override fun open(command: OpenSharedSocket) {
        if (!command.isAuthorizedBy(currentState())) return
        val oldSocket: WebSocket?
        synchronized(lock) {
            profileResolution?.cancel()
            profileResolution = null
            oldSocket = active?.socket
            active = null
            pendingOpen = command
            lastPublishedPtz = null
        }
        oldSocket?.close(NORMAL_CLOSE_CODE, SAFE_CLOSE_REASON)

        val job = scope.launch {
            val profile = runCatching { profiles.resolve(command.profileId) }.getOrNull()
            if (profile == null) {
                failPendingOpen(command, SafeTransportFailure.INVALID_PROFILE)
                return@launch
            }
            if (!command.isAuthorizedBy(currentState())) {
                clearPendingOpen(command)
                return@launch
            }
            val request = runCatching {
                Request.Builder().url(realtimeWebSocketUrl(profile)).build()
            }.getOrElse {
                failPendingOpen(command, SafeTransportFailure.INVALID_PROFILE)
                return@launch
            }
            val listener = Listener(command)
            val socket = runCatching { webSockets.newWebSocket(request, listener) }.getOrElse { error ->
                failPendingOpen(command, classifySocketFailure(error, null))
                return@launch
            }
            val retained = synchronized(lock) {
                if (pendingOpen == command) {
                    active = ActiveSocket(command, socket)
                    pendingOpen = null
                    profileResolution = null
                    true
                } else {
                    false
                }
            }
            if (!retained) socket.close(NORMAL_CLOSE_CODE, SAFE_CLOSE_REASON)
        }
        synchronized(lock) {
            if (pendingOpen == command) profileResolution = job else job.cancel()
        }
    }

    override fun close(command: CloseSharedSocket) {
        val socket = synchronized(lock) {
            if (pendingOpen?.operationId == command.operationId) {
                pendingOpen = null
                profileResolution?.cancel()
                profileResolution = null
            }
            active?.takeIf { it.open.operationId == command.operationId }?.also {
                it.intentionalClose = true
                active = null
                lastPublishedPtz = null
            }?.socket
        }
        socket?.close(NORMAL_CLOSE_CODE, SAFE_CLOSE_REASON)
    }

    override fun startConsumption(command: StartSocketConsumption) {
        if (!command.isAuthorizedBy(currentState())) return
        synchronized(lock) {
            active?.takeIf { it.open.operationId == command.operationId }?.consumption = command
        }
    }

    override fun publishOnConnect(command: PublishOnConnect) {
        if (!command.isAuthorizedBy(currentState())) return
        val socket = synchronized(lock) {
            active?.takeIf { it.open.operationId == command.operationId }?.socket
        } ?: return
        if (!socket.send(encodeOnConnect())) {
            failActiveSocket(socket, SafeTransportFailure.CONNECTION)
        }
    }

    override fun publishPtz(command: PublishPtz) {
        if (!command.isAuthorizedBy(currentState())) return
        val identity = command.generation to command.requestOperationId
        val socket = synchronized(lock) {
            if (lastPublishedPtz == identity) return
            active?.takeIf { it.open.operationId == command.socketOperationId }?.socket
        } ?: return
        if (socket.send(encodePtz(json, command))) {
            synchronized(lock) { lastPublishedPtz = identity }
        } else {
            failActiveSocket(socket, SafeTransportFailure.CONNECTION)
        }
    }

    private fun clearPendingOpen(command: OpenSharedSocket) {
        synchronized(lock) {
            if (pendingOpen == command) {
                pendingOpen = null
                profileResolution = null
            }
        }
    }

    private fun failPendingOpen(command: OpenSharedSocket, failure: SafeTransportFailure) {
        val shouldDispatch = synchronized(lock) {
            if (pendingOpen == command) {
                pendingOpen = null
                profileResolution = null
                true
            } else {
                false
            }
        }
        if (shouldDispatch && command.isAuthorizedBy(currentState())) {
            events.dispatch(RealtimeTransportEvent.SocketFailed(command.generation, command.operationId, failure))
        }
    }

    private fun failActiveSocket(socket: WebSocket, failure: SafeTransportFailure) {
        val command = synchronized(lock) {
            active?.takeIf { it.socket === socket && !it.terminalReported }?.also {
                it.terminalReported = true
                active = null
                lastPublishedPtz = null
            }?.open
        } ?: return
        socket.cancel()
        events.dispatch(RealtimeTransportEvent.SocketFailed(command.generation, command.operationId, failure))
    }

    private inner class Listener(
        private val command: OpenSharedSocket,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val valid = synchronized(lock) {
                active?.socket === webSocket && !requireNotNull(active).intentionalClose
            } && command.isAuthorizedBy(currentState())
            if (valid) {
                events.dispatch(RealtimeTransportEvent.SocketOpened(command.generation, command.operationId))
            } else {
                webSocket.close(NORMAL_CLOSE_CODE, SAFE_CLOSE_REASON)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = parser.parse(text) ?: return
            val consumption = synchronized(lock) {
                active?.takeIf { it.socket === webSocket && !it.intentionalClose }?.consumption
            } ?: return
            val state = currentState()
            val authorized = when (state.socket?.status) {
                SocketSessionStatus.SYNCHRONIZING -> BufferAuthorizedSocketMessage(
                    consumption.generation,
                    consumption.operationId,
                    message,
                    consumption.scopeToken,
                ).isAuthorizedBy(state)
                SocketSessionStatus.HEALTHY -> DeliverAuthorizedSocketMessage(
                    consumption.generation,
                    consumption.operationId,
                    message,
                    consumption.scopeToken,
                ).isAuthorizedBy(state)
                SocketSessionStatus.OPENING,
                null -> false
            }
            if (authorized) {
                events.dispatch(
                    RealtimeTransportEvent.SocketMessageReceived(
                        consumption.generation,
                        consumption.operationId,
                        message,
                    ),
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            reportUnexpectedClose(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failActiveSocket(webSocket, classifySocketFailure(t, response?.code))
        }

        private fun reportUnexpectedClose(webSocket: WebSocket) {
            val open = synchronized(lock) {
                active?.takeIf { it.socket === webSocket && !it.intentionalClose && !it.terminalReported }?.also {
                    it.terminalReported = true
                    active = null
                    lastPublishedPtz = null
                }?.open
            } ?: return
            events.dispatch(
                RealtimeTransportEvent.SocketClosedUnexpectedly(open.generation, open.operationId),
            )
        }
    }

    private data class ActiveSocket(
        val open: OpenSharedSocket,
        val socket: WebSocket,
        var consumption: StartSocketConsumption? = null,
        var intentionalClose: Boolean = false,
        var terminalReported: Boolean = false,
    )

    private companion object {
        const val NORMAL_CLOSE_CODE = 1000
        const val SAFE_CLOSE_REASON = "Opah connection closed"
    }
}

internal fun realtimeWebSocketUrl(profile: ConnectionProfile): String {
    val url = profile.apiBaseUrl.toHttpUrl().newBuilder().addPathSegment("ws").build()
    return if (url.isHttps) {
        url.toString().replaceFirst("https://", "wss://")
    } else {
        url.toString().replaceFirst("http://", "ws://")
    }
}

internal fun encodeOnConnect(): String =
    "{\"topic\":\"onConnect\",\"message\":\"\",\"retain\":false}"

internal fun encodePtz(json: Json, command: PublishPtz): String = buildString {
    append("{\"topic\":")
    append(json.encodeToString("${command.cameraId}/ptz"))
    append(",\"payload\":")
    append(json.encodeToString(command.operation.wireValue()))
    append(",\"retain\":false}")
}

private fun PtzOperation.wireValue(): String = when (this) {
    PtzOperation.MOVE_UP -> "MOVE_UP"
    PtzOperation.MOVE_DOWN -> "MOVE_DOWN"
    PtzOperation.MOVE_LEFT -> "MOVE_LEFT"
    PtzOperation.MOVE_RIGHT -> "MOVE_RIGHT"
    PtzOperation.ZOOM_IN -> "ZOOM_IN"
    PtzOperation.ZOOM_OUT -> "ZOOM_OUT"
    PtzOperation.STOP -> "STOP"
}

internal fun classifySocketFailure(error: Throwable, httpStatus: Int?): SafeTransportFailure = when {
    httpStatus == 401 -> SafeTransportFailure.AUTHENTICATION
    httpStatus == 403 -> SafeTransportFailure.AUTHORIZATION
    httpStatus != null && httpStatus in 300..399 -> SafeTransportFailure.INVALID_PROFILE
    httpStatus != null && httpStatus >= 500 -> SafeTransportFailure.SERVER
    error is UnknownHostException -> SafeTransportFailure.DNS
    error is SocketTimeoutException -> SafeTransportFailure.TIMEOUT
    error is ConnectException -> SafeTransportFailure.CONNECTION
    else -> SafeTransportFailure.CONNECTION
}
