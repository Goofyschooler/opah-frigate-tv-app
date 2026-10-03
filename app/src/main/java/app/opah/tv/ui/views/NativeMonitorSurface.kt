package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.opah.tv.playback.AuthenticatedHttpLive
import app.opah.tv.playback.HttpsLivePlayer
import app.opah.tv.playback.LivePlayer
import app.opah.tv.playback.LivePlaybackOptions
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opah.tv.OpahApplication
import app.opah.tv.R
import app.opah.tv.data.model.Camera
import app.opah.tv.monitor.MonitorPhase
import app.opah.tv.monitor.MonitorCameraPin
import app.opah.tv.monitor.MonitorPresentationKind
import app.opah.tv.monitor.MonitorPreset
import app.opah.tv.playback.compatibility.PlaybackCompatibilityState
import app.opah.tv.playback.compatibility.PlaybackResult
import app.opah.tv.playback.media3.PlayerViewMedia3VideoOutputTarget
import app.opah.tv.playback.media3.SingleLivePlaybackRequestId
import app.opah.tv.ui.MonitorModeUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
@SuppressLint("ViewConstructor", "SetTextI18n")
internal class NativeMonitorSurface(
    private val activity: ComponentActivity,
    initialState: MonitorModeUiState,
    private val prepareHttpLive: (String) -> AuthenticatedHttpLive?,
    private val cachedBitmap: (String) -> Bitmap?,
    private val refreshBitmap: suspend (String, Int) -> Bitmap?,
    private val onPreset: (MonitorPreset) -> Unit,
    private val onManualCamera: (String) -> Unit,
    private val onPlaybackReady: () -> Unit,
    private val onPlaybackFailed: () -> Unit,
    private val onKeepScreenAwake: (Boolean) -> Unit,
    private val onAudioEnabled: (Boolean) -> Unit,
    private val onExitMinutes: (Int?) -> Unit,
    private val onBack: () -> Unit,
) : FrameLayout(activity) {
    private val application = activity.application as OpahApplication
    private val visualHost = FrameLayout(activity)
    private val title = TextView(activity)
    private val status = TextView(activity)
    private val header = LinearLayout(activity)
    private val controls = LinearLayout(activity)
    private val controlsScroller = HorizontalScrollView(activity)
    private val presetButtons = linkedMapOf<MonitorPreset, TextView>()
    private val cameraLabels = mutableListOf<View>()
    private val audioButton: TextView
    private val awakeButton: TextView
    private val exitButton: TextView
    private val gridButton: TextView
    private val cameraPin = MonitorCameraPin()
    private var httpPlayer: ExoPlayer? = null
    private var httpBackend: LivePlayer? = null
    private var httpPlayerView: PlayerView? = null
    private var pinnedStatus = "Connecting live video over HTTPS"
    private var state = initialState
    private var visualKey: String? = null
    private var visualJob: Job? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) {
            visualJob?.cancel()
            stopHttpLive()
            visualKey = null
        } else if (event == Lifecycle.Event.ON_START) {
            update(state)
        }
    }
    private var chromeHideJob: Job? = null
    private var chromeVisible = true

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        addView(visualHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        header.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
            setBackgroundColor(0xB8000000.toInt())
            this@NativeMonitorSurface.title.apply {
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }
            this@NativeMonitorSurface.status.apply {
                textSize = 13f
                setTextColor(0xFFD5DFEC.toInt())
            }
            addView(this@NativeMonitorSurface.title)
            addView(this@NativeMonitorSurface.status)
        }
        addView(
            header,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                leftMargin = activity.dp(16)
                topMargin = activity.dp(14)
            },
        )
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER_VERTICAL
        controls.setPadding(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(6))
        controls.setBackgroundColor(0xD9000000.toInt())
        controls.addView(controlButton("Exit", R.drawable.ic_chevron_left, onBack))
        controls.addView(panelLabel("View"))
        MonitorPreset.entries.forEach { preset ->
            val button = controlButton(preset.displayLabel()) {
                cameraPin.clear()
                update(state)
                onPreset(preset)
            }
            presetButtons[preset] = button
            controls.addView(button)
        }
        controls.addView(divider())
        gridButton = controlButton("Show grid") {
            cameraPin.clear()
            update(state)
            presetButtons[state.preset]?.requestFocus()
        }
        controls.addView(gridButton)
        audioButton = controlButton("", R.drawable.ic_volume_off) { onAudioEnabled(!state.audioEnabled) }
        awakeButton = controlButton("") { onKeepScreenAwake(!state.keepScreenAwake) }
        exitButton = controlButton("") { onExitMinutes(nextExitMinutes(state.exitAfterMinutes)) }
        controls.addView(audioButton)
        controls.addView(awakeButton)
        controls.addView(exitButton)
        controlsScroller.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(controls, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(
            controlsScroller,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        update(initialState)
        activity.lifecycle.addObserver(lifecycleObserver)
        controls.post {
            presetButtons[state.preset]?.requestFocus()
            scheduleChromeHide()
        }
    }

    fun update(updated: MonitorModeUiState) {
        state = updated
        val visible = updated.cameras.filter { it.name in updated.visibleCameraIds }
        val pinnedId = cameraPin.reconcile(updated.preset, visible.map { it.name })
        val pinned = visible.firstOrNull { it.name == pinnedId }
        gridButton.visibility = if (pinned != null) View.VISIBLE else View.GONE
        keepScreenOn = updated.keepScreenAwake
        title.text = updated.title
        status.text = if (pinned != null) {
            "Pinned ${pinned.displayName} · $pinnedStatus"
        } else monitorStatus(updated)
        presetButtons.forEach { (preset, button) -> button.isSelected = preset == updated.preset }
        audioButton.text = if (updated.audioEnabled) "Audio on" else "Audio off"
        audioButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (updated.audioEnabled) R.drawable.ic_volume_on else R.drawable.ic_volume_off,
            0,
            0,
            0,
        )
        audioButton.compoundDrawablesRelative.forEach { drawable -> drawable?.setTint(Color.WHITE) }
        audioButton.isSelected = updated.audioEnabled
        audioButton.visibility = View.VISIBLE
        httpPlayer?.volume = if (updated.audioEnabled) 1f else 0f
        awakeButton.text = if (updated.keepScreenAwake) "Stay awake" else "Allow sleep"
        awakeButton.isSelected = updated.keepScreenAwake
        exitButton.text = updated.exitAfterMinutes?.let { "Timer: $it min" } ?: "Timer: Never"
        exitButton.isSelected = updated.exitAfterMinutes != null
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            visualJob?.cancel()
            stopHttpLive()
            visualKey = null
            return
        }

        val promoted = updated.arbitration.promotion?.cameraId
            ?.let { id -> updated.cameras.firstOrNull { it.name == id } }
        val patrol = updated.arbitration.patrolCameraId
            ?.let { id -> updated.cameras.firstOrNull { it.name == id } }
        val nextVisualKey = when {
            pinned != null -> "pinned:${pinned.name}"
            promoted != null &&
                updated.arbitration.promotion.presentation == MonitorPresentationKind.LIVE &&
                updated.liveCompatibilityRequestId != null ->
                "live:${updated.liveCompatibilityRequestId}:${updated.audioEnabled}"
            promoted != null -> "snapshot:${promoted.name}"
            patrol != null -> "snapshot:${patrol.name}"
            else -> "grid:${visible.joinToString { it.name }}"
        }
        if (nextVisualKey == visualKey) return
        visualKey = nextVisualKey
        visualJob?.cancel()
        stopHttpLive()
        visualJob = null
        visualHost.removeAllViews()
        cameraLabels.clear()
        when {
            pinned != null -> showHttpLive(pinned)
            nextVisualKey.startsWith("live:") -> showLive(requireNotNull(updated.liveCompatibilityRequestId), !updated.audioEnabled)
            promoted != null -> showSnapshot(promoted)
            patrol != null -> showSnapshot(patrol)
            visible.isNotEmpty() -> showGrid(visible)
            else -> visualHost.addView(
                statusLabel("No cameras are available in this view"),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }
    }

    fun close() {
        activity.lifecycle.removeObserver(lifecycleObserver)
        stopHttpLive()
        cameraPin.clear()
        chromeHideJob?.cancel()
        chromeHideJob = null
        visualJob?.cancel()
        visualJob = null
        keepScreenOn = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (cameraPin.cameraId != null) {
                    cameraPin.clear()
                    update(state)
                    showChrome(requestFocus = true)
                    return true
                }
                onBack()
                return true
            }
            if (!chromeVisible) {
                showChrome(requestFocus = true)
                return true
            }
            scheduleChromeHide()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun stopHttpLive() {
        httpPlayerView?.player = null
        httpPlayerView = null
        httpBackend?.release()
        httpBackend = null
        httpPlayer = null
    }

    private fun setPinnedStatus(camera: Camera, message: String) {
        pinnedStatus = message
        status.text = "Pinned ${camera.displayName} · $message"
    }

    private fun showHttpLive(camera: Camera) {
        val request = prepareHttpLive(camera.name)
        if (request == null || !request.isAuthorized()) {
            setPinnedStatus(camera, "Live stream unavailable or access denied")
            return
        }
        setPinnedStatus(camera, "Connecting live video over HTTPS")
        val view = PlayerView(activity).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setKeepContentOnPlayerReset(false)
            setBackgroundColor(Color.BLACK)
        }
        visualHost.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val backend = HttpsLivePlayer(activity, application.container.httpClient)
        httpBackend = backend
        val player = backend.player
        httpPlayer = player
        httpPlayerView = view
        view.player = player
        var firstFrame = false
        var failed = false
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (httpPlayer !== player) return
                if (!request.isAuthorized()) {
                    failed = true
                    setPinnedStatus(camera, "Access changed; playback stopped")
                    stopHttpLive()
                    return
                }
                firstFrame = true
                setPinnedStatus(camera, "Live HTTPS video")
            }

            override fun onPlayerError(error: PlaybackException) {
                if (httpPlayer !== player) return
                failed = true
                setPinnedStatus(camera, "HTTPS playback failed: ${error.errorCodeName}")
                stopHttpLive()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (httpPlayer !== player) return
                if (playbackState == Player.STATE_ENDED) {
                    failed = true
                    setPinnedStatus(camera, "Live connection ended; return to grid and retry")
                    stopHttpLive()
                } else if (playbackState == Player.STATE_BUFFERING && firstFrame) {
                    setPinnedStatus(camera, "Live video buffering")
                } else if (playbackState == Player.STATE_READY && firstFrame) {
                    setPinnedStatus(camera, "Live HTTPS video")
                }
            }
        })
        backend.prepare(request.uri, LivePlaybackOptions(videoOnly = !state.audioEnabled))
        visualJob = activity.lifecycleScope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                while (isActive && !failed && httpPlayer === player) {
                    if (!request.isAuthorized()) {
                        setPinnedStatus(camera, "Access changed; playback stopped")
                        break
                    }
                    if (!firstFrame && android.os.SystemClock.elapsedRealtime() - started >= 30_000L) {
                        setPinnedStatus(camera, "HTTPS first-frame timeout; return to grid and retry")
                        break
                    }
                    delay(250L)
                }
            } finally {
                if (httpPlayer === player) stopHttpLive()
            }
        }
    }

    private fun showLive(requestId: String, videoOnly: Boolean) {
        val playerView = PlayerView(activity).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            isFocusable = false
            setBackgroundColor(Color.BLACK)
        }
        visualHost.addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visualJob = activity.lifecycleScope.launch {
            val session = try {
                application.container.singleLivePlaybackCoordinator.open(
                    SingleLivePlaybackRequestId(requestId),
                    PlayerViewMedia3VideoOutputTarget(
                        playerView = playerView,
                        onAttached = { player -> player.volume = if (videoOnly) 0f else 1f },
                        onDetached = {},
                    ),
                    videoOnly = videoOnly,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onPlaybackFailed()
                return@launch
            }
            var readyReported = false
            var failureReported = false
            try {
                session.snapshots.collect { snapshot ->
                    when (val playback = snapshot.state) {
                        is PlaybackCompatibilityState.Verified,
                        is PlaybackCompatibilityState.UnpersistedLive,
                        is PlaybackCompatibilityState.Persisting,
                        -> if (!readyReported) {
                            readyReported = true
                            onPlaybackReady()
                        }
                        is PlaybackCompatibilityState.Finished -> if (
                            playback.result !is PlaybackResult.VerifiedLive &&
                            playback.result !is PlaybackResult.UnpersistedLive &&
                            playback.result !is PlaybackResult.Cancelled &&
                            !failureReported
                        ) {
                            failureReported = true
                            onPlaybackFailed()
                        }
                        else -> Unit
                    }
                }
            } finally {
                withContext(NonCancellable) { session.close() }
            }
        }
    }

    private fun showSnapshot(camera: Camera) {
        val image = snapshotView(camera.name)
        visualHost.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val cameraLabel = statusLabel(camera.displayName).apply {
            visibility = if (chromeVisible) View.GONE else View.VISIBLE
        }
        cameraLabels += cameraLabel
        visualHost.addView(
            cameraLabel,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = activity.dp(16)
                bottomMargin = activity.dp(16)
            },
        )
        visualJob = activity.lifecycleScope.launch {
            while (isActive) {
                refreshBitmap(camera.name, MONITOR_SNAPSHOT_HEIGHT)?.let(image::setImageBitmap)
                delay(SNAPSHOT_REFRESH_MILLIS)
            }
        }
    }

    private fun showGrid(cameras: List<Camera>) {
        val shown = cameras.take(4)
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        shown.chunked(2).forEach { rowCameras ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            rowCameras.forEach { camera ->
                row.addView(cameraTile(camera), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            if (rowCameras.size == 1) row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        visualHost.addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visualJob = activity.lifecycleScope.launch {
            while (isActive) {
                shown.forEach { camera ->
                    rows.findViewWithTag<ImageView>("monitor:image:${camera.name}")
                        ?.let { image -> refreshBitmap(camera.name, MONITOR_GRID_HEIGHT)?.let(image::setImageBitmap) }
                }
                delay(SNAPSHOT_REFRESH_MILLIS)
            }
        }
    }

    private fun cameraTile(camera: Camera): View = FrameLayout(activity).apply {
        tag = "monitor:tile:${camera.name}"
        isFocusable = true
        isClickable = true
        background = activity.nativeFlatOverlayFocusableBackground()
        setPadding(activity.dp(2), activity.dp(2), activity.dp(2), activity.dp(2))
        addView(snapshotView(camera.name), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val cameraLabel = statusLabel(camera.displayName).apply {
            visibility = if (chromeVisible) View.GONE else View.VISIBLE
        }
        cameraLabels += cameraLabel
        addView(
            cameraLabel,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = activity.dp(10)
                bottomMargin = activity.dp(10)
            },
        )
        setOnClickListener {
            if (state.preset == MonitorPreset.FIXED) {
                cameraPin.select(camera.name, state.cameras
                    .filter { it.name in state.visibleCameraIds }.map { it.name })
                update(state)
                showChrome(requestFocus = false)
                gridButton.requestFocus()
            } else {
                onManualCamera(camera.name)
            }
        }
    }

    private fun snapshotView(cameraName: String): ImageView = ImageView(activity).apply {
        tag = "monitor:image:$cameraName"
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(NativeTheme.palette.panel)
        cachedBitmap(cameraName)?.let(::setImageBitmap)
    }

    private fun monitorStatus(value: MonitorModeUiState): String {
        val promoted = value.arbitration.promotion?.cameraId
            ?.let { id -> value.cameras.firstOrNull { it.name == id } }
        val patrol = value.arbitration.patrolCameraId
            ?.let { id -> value.cameras.firstOrNull { it.name == id } }
        return value.statusMessage ?: when {
            value.arbitration.phase == MonitorPhase.RECOVERING -> "Reconnecting"
            promoted != null -> "Watching ${promoted.displayName}"
            patrol != null -> "Patrolling ${patrol.displayName}"
            value.preset == MonitorPreset.FIXED -> "Fixed grid · Select a camera for pinned HTTPS live video"
            else -> "Watching for activity"
        }
    }

    private fun controlButton(
        label: String,
        iconRes: Int? = null,
        action: () -> Unit,
    ): TextView = TextView(activity).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        background = activity.nativeFlatOverlayFocusableBackground()
        isFocusable = true
        isClickable = true
        iconRes?.let { resource ->
            setCompoundDrawablesRelativeWithIntrinsicBounds(resource, 0, 0, 0)
            compoundDrawablePadding = activity.dp(5)
            compoundDrawablesRelative.forEach { drawable -> drawable?.setTint(Color.WHITE) }
        }
        setPadding(activity.dp(10), activity.dp(5), activity.dp(10), activity.dp(5))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(40)).apply {
            marginStart = activity.dp(2)
            marginEnd = activity.dp(2)
        }
        setOnClickListener {
            action()
            showChrome(requestFocus = false)
        }
    }

    private fun panelLabel(label: String): TextView = TextView(activity).apply {
        text = label
        textSize = 12f
        setTextColor(0xFFD5DFEC.toInt())
        setPadding(activity.dp(9), 0, activity.dp(5), 0)
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, activity.dp(40))
    }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(0x66FFFFFF)
        layoutParams = LinearLayout.LayoutParams(activity.dp(1), activity.dp(24)).apply {
            marginStart = activity.dp(7)
            marginEnd = activity.dp(7)
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    private fun statusLabel(label: String): TextView = TextView(activity).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        setPadding(activity.dp(10), activity.dp(7), activity.dp(10), activity.dp(7))
        setBackgroundColor(0xB3000000.toInt())
    }

    private fun showChrome(requestFocus: Boolean) {
        chromeVisible = true
        header.visibility = View.VISIBLE
        controlsScroller.visibility = View.VISIBLE
        cameraLabels.forEach { it.visibility = View.GONE }
        if (requestFocus || findFocus() == null || findFocus() === this) {
            presetButtons[state.preset]?.requestFocus()
        }
        scheduleChromeHide()
    }

    private fun hideChrome() {
        chromeVisible = false
        header.visibility = View.GONE
        controlsScroller.visibility = View.GONE
        cameraLabels.forEach { it.visibility = View.VISIBLE }
        requestFocus()
    }

    private fun scheduleChromeHide() {
        chromeHideJob?.cancel()
        chromeHideJob = activity.lifecycleScope.launch {
            delay(CHROME_HIDE_MILLIS)
            hideChrome()
        }
    }

    private fun MonitorPreset.displayLabel(): String = when (this) {
        MonitorPreset.FIXED -> "Fixed"
        MonitorPreset.CALM -> "Calm"
        MonitorPreset.ACTIVE -> "Active"
        MonitorPreset.PATROL -> "Patrol"
    }

    private fun nextExitMinutes(current: Int?): Int? = when (current) {
        null -> 15
        15 -> 30
        30 -> 60
        60 -> null
        else -> null
    }

    private companion object {
        const val MONITOR_SNAPSHOT_HEIGHT = 720
        const val MONITOR_GRID_HEIGHT = 360
        const val SNAPSHOT_REFRESH_MILLIS = 5_000L
        const val CHROME_HIDE_MILLIS = 5_000L
    }
}
