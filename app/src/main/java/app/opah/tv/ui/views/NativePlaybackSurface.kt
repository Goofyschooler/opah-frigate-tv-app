package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.isEmpty
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opah.tv.OpahApplication
import app.opah.tv.PictureInPictureRequest
import app.opah.tv.R
import app.opah.tv.BuildConfig
import app.opah.tv.pipAspectRatio
import app.opah.tv.playback.LivePlaybackOptions
import app.opah.tv.playback.PlaybackKind
import app.opah.tv.playback.PlaybackRequest
import app.opah.tv.playback.RecordedPlayerFactory
import app.opah.tv.playback.compatibility.AudioMode
import app.opah.tv.playback.compatibility.DecoderMode
import app.opah.tv.playback.compatibility.PlaybackCandidate
import app.opah.tv.playback.compatibility.PlaybackCompatibilityState
import app.opah.tv.playback.compatibility.PlaybackResult
import app.opah.tv.playback.compatibility.TransportMode
import app.opah.tv.playback.media3.PlayerViewMedia3VideoOutputTarget
import app.opah.tv.ui.DOCUMENTATION_URI_PREFIX
import app.opah.tv.ui.DocumentationImageStore
import app.opah.tv.ui.boundedSeekPosition
import app.opah.tv.playback.media3.SingleLivePlaybackRequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class NativePlaybackControl(
    val key: String,
    val label: String,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

internal enum class NativePlaybackControlPlacement { CONTEXT, MORE }

internal fun nativePlaybackControlPlacement(@Suppress("UNUSED_PARAMETER") key: String): NativePlaybackControlPlacement =
    NativePlaybackControlPlacement.CONTEXT

internal fun nativePlaybackResizeMode(stretched: Boolean): Int = if (stretched) {
    AspectRatioFrameLayout.RESIZE_MODE_FILL
} else {
    AspectRatioFrameLayout.RESIZE_MODE_FIT
}

internal const val AUTOMATIC_REVIEW_WATCH_MILLIS = 8_000L

internal fun automaticReviewWatchThresholdMillis(durationMillis: Long?): Long =
    durationMillis?.takeIf { it > 0L }?.coerceAtMost(AUTOMATIC_REVIEW_WATCH_MILLIS)
        ?: AUTOMATIC_REVIEW_WATCH_MILLIS

internal fun watchedEnoughForAutomaticReview(watchedMillis: Long, durationMillis: Long?): Boolean =
    watchedMillis >= automaticReviewWatchThresholdMillis(durationMillis)

@UnstableApi
@SuppressLint("ViewConstructor")
internal class NativePlaybackSurface(
    private val activity: ComponentActivity,
    val request: PlaybackRequest,
    private val preferRtpTcp: Boolean,
    startMuted: Boolean,
    private val diagnosticsEnabled: Boolean = false,
    initialControls: List<NativePlaybackControl> = emptyList(),
    initialActionMessage: String? = null,
    pictureInPictureAvailable: Boolean = false,
    private val onEnterPictureInPicture: (PictureInPictureRequest) -> Boolean = { false },
    private val onCompatibilityTestStatus: (String) -> Unit = {},
    private val onClose: () -> Unit,
    private val onAutomaticReviewWatchThresholdReached: () -> Unit = {},
    private val onPlaybackEnded: () -> Unit = {},
) : FrameLayout(activity) {
    private val application = activity.application as OpahApplication
    private val documentationPlayback = BuildConfig.DOCUMENTATION_MODE &&
        request.uri.startsWith(DOCUMENTATION_URI_PREFIX)
    private val playerView = PlayerView(activity).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        isFocusable = false
        setBackgroundColor(Color.BLACK)
    }
    private val documentationImage = if (documentationPlayback) {
        ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val cameraName = request.cameraName
                ?: request.uri.removePrefix(DOCUMENTATION_URI_PREFIX).substringBefore('?')
            setImageBitmap(DocumentationImageStore(activity).camera(cameraName)?.bitmap)
            contentDescription = "Fictional documentation camera scene"
        }
    } else {
        null
    }
    private val title = TextView(activity).apply {
        setTextColor(Color.WHITE)
        textSize = 20f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setShadowLayer(5f, 0f, 2f, Color.BLACK)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        text = request.title
    }
    private val status = TextView(activity).apply {
        setTextColor(0xFFD7E1EE.toInt())
        textSize = 13f
        maxLines = 8
        text = request.detail ?: if (request.kind == PlaybackKind.LIVE) "Live" else "Recording"
        setShadowLayer(5f, 0f, 2f, Color.BLACK)
    }
    private val progress = TextView(activity).apply {
        setTextColor(0xFFD7E1EE.toInt())
        textSize = 12f
        setShadowLayer(5f, 0f, 2f, Color.BLACK)
        visibility = if (request.kind == PlaybackKind.RECORDED) View.VISIBLE else View.GONE
    }
    private val primaryControls = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val contextControls = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private val mainControlRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val mainControlScroller = HorizontalScrollView(activity).apply {
        isFocusable = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        addView(mainControlRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private val moreControls = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val moreScroller = HorizontalScrollView(activity).apply {
        isFocusable = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        visibility = View.GONE
        addView(moreControls, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private val actionMessage = TextView(activity).apply {
        setTextColor(0xFFE6F0FB.toInt())
        textSize = 12f
        gravity = Gravity.CENTER
        visibility = View.GONE
        setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
    }
    private val controlHint = TextView(activity).apply {
        setTextColor(0xFFE6F0FB.toInt())
        textSize = 12f
        gravity = Gravity.CENTER
        minHeight = activity.dp(20)
        includeFontPadding = false
    }
    private val timeline = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1_000
        progressTintList = ColorStateList.valueOf(NativeTheme.palette.focus)
        progressBackgroundTintList = ColorStateList.valueOf(0xFF586575.toInt())
    }
    private val progressStrip = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = if (request.kind == PlaybackKind.RECORDED) View.VISIBLE else View.GONE
        isFocusable = request.kind == PlaybackKind.RECORDED
        isClickable = request.kind == PlaybackKind.RECORDED
        tag = "recorded:timeline"
        background = activity.nativeFlatOverlayFocusableBackground()
        contentDescription = "Playback timeline, use left and right to seek 10 seconds"
        setPadding(activity.dp(10), 0, activity.dp(10), activity.dp(3))
        setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || request.kind != PlaybackKind.RECORDED) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-10_000L); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(10_000L); true }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                -> { togglePlayback(); true }
                else -> false
            }
        }
        onFocusChangeListener = OnFocusChangeListener { _, focused ->
            if (focused) controlHint.setText(R.string.native_playback_seek_help)
        }
        addView(progress, LinearLayout.LayoutParams(activity.dp(92), LayoutParams.WRAP_CONTENT))
        addView(
            timeline,
            LinearLayout.LayoutParams(0, activity.dp(3), 1f).apply { marginStart = activity.dp(8) },
        )
    }
    private val diagnostics = TextView(activity).apply {
        setTextColor(0xFFE6F0FB.toInt())
        textSize = 12f
        setBackgroundColor(0xCC000000.toInt())
        setPadding(activity.dp(10), activity.dp(7), activity.dp(10), activity.dp(7))
        maxWidth = activity.dp(520)
        visibility = View.GONE
    }
    private var player: ExoPlayer? = null
    private var liveJob: Job? = null
    private var progressJob: Job? = null
    private var muted = startMuted
    private var compatibilityTestExitScheduled = false
    private var playbackEndedReported = false
    private var automaticReviewThresholdReported = false
    private var watchedPlaybackMillis = 0L
    private var lastWatchSampleElapsedRealtime: Long? = null
    private var documentationPositionMillis = 8_000L
    private val documentationDurationMillis = 24_000L
    private var pictureInPictureReady = false
    private var pictureInPictureActive = false
    private var chromeVisible = true
    private var moreExpanded = false
    private var diagnosticsVisible = false
    private var chromeHideJob: Job? = null
    private val coordinatorOwned = request.kind == PlaybackKind.LIVE && request.liveCompatibilityRequestId != null
    private val playPause = controlButton("Pause", R.drawable.ic_pause, "Pause playback", emphasized = true) {
        togglePlayback()
    }
    private val seekBack = controlButton("10 sec", R.drawable.ic_replay, "Back 10 seconds") { seekBy(-10_000L) }.apply {
        visibility = if (request.kind == PlaybackKind.RECORDED) View.VISIBLE else View.GONE
    }
    private val seekForward = controlButton("10 sec", R.drawable.ic_forward, "Forward 10 seconds") { seekBy(10_000L) }.apply {
        visibility = if (request.kind == PlaybackKind.RECORDED) View.VISIBLE else View.GONE
    }
    private val audio = controlButton(
        if (muted) "Muted" else "Audio",
        if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on,
        if (muted) "Turn on audio" else "Mute audio",
    ) { toggleAudio() }
    private val retry = controlButton("Retry", R.drawable.ic_retry, "Try playback again") { retryPlayback() }.apply {
        visibility = View.GONE
    }
    private val pictureInPicture = controlButton(
        "PiP",
        R.drawable.ic_picture_in_picture,
        "Picture-in-picture",
    ) { enterPictureInPicture() }.apply {
        isEnabled = false
        alpha = 0.45f
        visibility = if (pictureInPictureAvailable && request.kind == PlaybackKind.LIVE) View.VISIBLE else View.GONE
    }
    private val diagnosticButton = controlButton(
        "Info",
        R.drawable.ic_playback_info,
        "Playback information",
    ) { toggleDiagnostics() }.apply {
        visibility = if (diagnosticsEnabled) View.VISIBLE else View.GONE
    }
    private val moreButton = controlButton("More", R.drawable.ic_more, "More playback actions") { toggleMoreControls() }.apply {
        tag = "more"
        visibility = View.GONE
    }
    private val topPanel: LinearLayout
    private val bottomPanel: LinearLayout

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        documentationImage?.let { image ->
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        topPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(9))
            setBackgroundColor(0x77000000)
            addView(title)
            addView(status)
        }
        addView(
            topPanel,
            LayoutParams(activity.dp(680), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = activity.dp(12)
                marginStart = activity.dp(12)
            },
        )
        mainControlRow.addView(controlButton("Back", R.drawable.ic_chevron_left, "Back", action = onClose))
        mainControlRow.addView(contextControls)
        mainControlRow.addView(primaryControls)
        primaryControls.addView(seekBack)
        primaryControls.addView(playPause)
        primaryControls.addView(seekForward)
        primaryControls.addView(audio)
        primaryControls.addView(retry)
        primaryControls.addView(pictureInPicture)
        primaryControls.addView(diagnosticButton)
        primaryControls.addView(moreButton)
        bottomPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(activity.dp(12), activity.dp(5), activity.dp(12), activity.dp(8))
            setBackgroundColor(0xC7000000.toInt())
            addView(
                actionMessage,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
            addView(progressStrip, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(controlHint, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(20)))
            addView(moreScroller, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(mainControlScroller, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(
            bottomPanel,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        addView(
            diagnostics,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = activity.dp(22)
                marginEnd = activity.dp(24)
            },
        )
        if (documentationPlayback) {
            startDocumentationPlayback()
        } else if (request.kind == PlaybackKind.LIVE && request.liveCompatibilityRequestId != null) {
            startCompatibilityPlayback(request.liveCompatibilityRequestId)
        } else {
            startDirectPlayback()
        }
        updateControls(initialControls, initialActionMessage)
        updateDiagnostics()
        if (request.compatibilityTest) {
            bottomPanel.visibility = View.GONE
        } else {
            primaryControls.post { playPause.requestFocus() }
        }
    }

    fun updateControls(updated: List<NativePlaybackControl>, message: String?) {
        val focusedKey = findFocus()?.tag as? String
        val dynamicControlHadFocus = focusedKey != null
        playerView.resizeMode = nativePlaybackResizeMode(
            updated.firstOrNull { it.key == "stretch" }?.selected == true,
        )
        contextControls.removeAllViews()
        moreControls.removeAllViews()
        updated.forEach { control ->
            val button = controlButton(
                label = control.label,
                iconRes = nativePlaybackControlIcon(control.key),
                accessibilityLabel = control.label,
                action = control.onClick,
            ).apply {
                tag = control.key
                isEnabled = control.enabled
                isSelected = control.selected
                alpha = if (control.enabled) 1f else 0.5f
            }
            when (nativePlaybackControlPlacement(control.key)) {
                NativePlaybackControlPlacement.CONTEXT -> contextControls.addView(button)
                NativePlaybackControlPlacement.MORE -> moreControls.addView(button)
            }
        }
        contextControls.visibility = if (contextControls.isEmpty()) View.GONE else View.VISIBLE
        moreButton.visibility = if (moreControls.isEmpty()) View.GONE else View.VISIBLE
        if (moreControls.isEmpty()) moreExpanded = false
        updateMoreControlsVisibility()
        actionMessage.text = message.orEmpty()
        actionMessage.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        if (dynamicControlHadFocus) {
            bottomPanel.post {
                val target = findViewWithTag<View>(focusedKey)?.takeIf { it.isEnabled && it.isFocusable }
                    ?: contextControls.firstEnabledFocusableChild()
                    ?: primaryControls.firstEnabledFocusableChild()
                    ?: moreControls.firstEnabledFocusableChild()
                target?.requestFocus()
            }
        }
        if (!message.isNullOrBlank()) showChrome(requestFocus = false)
    }

    fun close() {
        chromeHideJob?.cancel()
        chromeHideJob = null
        liveJob?.cancel()
        liveJob = null
        progressJob?.cancel()
        progressJob = null
        playerView.player = null
        if (!coordinatorOwned) player?.release()
        player = null
    }

    fun setPictureInPictureActive(active: Boolean) {
        pictureInPictureActive = active
        if (active) {
            chromeHideJob?.cancel()
            topPanel.visibility = View.GONE
            bottomPanel.visibility = View.GONE
            diagnostics.visibility = View.GONE
        } else {
            showChrome(requestFocus = false)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (moreExpanded) {
                    moreExpanded = false
                    updateMoreControlsVisibility()
                    moreButton.requestFocus()
                } else {
                    onClose()
                }
                return true
            }
            val chromeActuallyVisible = topPanel.isVisible &&
                (request.compatibilityTest || bottomPanel.isVisible)
            if ((!chromeVisible || !chromeActuallyVisible) && !pictureInPictureActive) {
                showChrome(requestFocus = true)
                return true
            }
            scheduleChromeHide()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun controlButton(
        label: String,
        iconRes: Int?,
        accessibilityLabel: String,
        emphasized: Boolean = false,
        action: () -> Unit,
    ): TextView = TextView(activity).apply {
        text = if (iconRes == null) label else ""
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        isFocusable = true
        isClickable = true
        contentDescription = accessibilityLabel
        background = activity.nativeFlatOverlayFocusableBackground()
        if (emphasized) setTypeface(typeface, android.graphics.Typeface.BOLD)
        iconRes?.let { resource ->
            setCompoundDrawablesRelativeWithIntrinsicBounds(resource, 0, 0, 0)
            compoundDrawablePadding = 0
            compoundDrawablesRelative.forEach { drawable -> drawable?.setTint(Color.WHITE) }
        }
        setPadding(activity.dp(8), activity.dp(5), activity.dp(8), activity.dp(5))
        layoutParams = LinearLayout.LayoutParams(
            if (iconRes == null) LayoutParams.WRAP_CONTENT else activity.dp(42),
            activity.dp(40),
        ).apply {
            marginStart = activity.dp(2)
            marginEnd = activity.dp(2)
        }
        onFocusChangeListener = OnFocusChangeListener { _, focused ->
            if (focused) {
                controlHint.text = accessibilityLabel
            } else if (controlHint.text == accessibilityLabel) {
                controlHint.text = ""
            }
        }
        setOnClickListener { action() }
    }

    private fun nativePlaybackControlIcon(key: String): Int? = when (key) {
        "previous" -> R.drawable.ic_chevron_left
        "next" -> R.drawable.ic_chevron_right
        "next-activity" -> R.drawable.ic_forward
        "return-live" -> R.drawable.ic_videocam
        "rewind" -> R.drawable.ic_replay
        "reviewed" -> R.drawable.ic_mark_reviewed
        "save" -> R.drawable.ic_save_recording
        "stretch" -> R.drawable.ic_stretch
        "recording" -> R.drawable.ic_videocam
        "snapshot" -> R.drawable.ic_snapshot
        else -> null
    }

    private fun toggleMoreControls() {
        moreExpanded = !moreExpanded
        updateMoreControlsVisibility()
        if (moreExpanded) {
            val target = (0 until moreControls.childCount)
                .map(moreControls::getChildAt)
                .firstOrNull { it.isEnabled && it.isFocusable }
            target?.requestFocus()
        } else {
            moreButton.requestFocus()
        }
        showChrome(requestFocus = false)
    }

    private fun updateMoreControlsVisibility() {
        moreScroller.visibility = if (moreExpanded && moreControls.isNotEmpty()) View.VISIBLE else View.GONE
        moreButton.isSelected = moreExpanded
    }

    private fun toggleDiagnostics() {
        diagnosticsVisible = !diagnosticsVisible
        diagnostics.visibility = if (diagnosticsVisible && chromeVisible) View.VISIBLE else View.GONE
        diagnosticButton.isSelected = diagnosticsVisible
        updateDiagnostics()
        if (diagnosticsVisible) chromeHideJob?.cancel() else scheduleChromeHide()
    }

    private fun showChrome(requestFocus: Boolean) {
        if (pictureInPictureActive) return
        chromeVisible = true
        topPanel.visibility = View.VISIBLE
        bottomPanel.visibility = if (request.compatibilityTest) View.GONE else View.VISIBLE
        diagnostics.visibility = if (diagnosticsVisible) View.VISIBLE else View.GONE
        val focused = findFocus()
        if (
            !request.compatibilityTest &&
            (requestFocus || focused == null || focused === this || !bottomPanel.isAncestorOf(focused))
        ) {
            playPause.requestFocus()
        }
        scheduleChromeHide()
    }

    private fun hideChrome() {
        if (pictureInPictureActive || request.compatibilityTest || player?.isPlaying != true) return
        chromeVisible = false
        topPanel.visibility = View.GONE
        bottomPanel.visibility = View.GONE
        diagnostics.visibility = View.GONE
        requestFocus()
    }

    private fun scheduleChromeHide() {
        chromeHideJob?.cancel()
        chromeHideJob = null
        if (
            request.compatibilityTest ||
            pictureInPictureActive ||
            moreExpanded ||
            diagnosticsVisible ||
            player?.isPlaying != true
        ) return
        chromeHideJob = activity.lifecycleScope.launch {
            delay(4_500)
            hideChrome()
        }
    }

    private fun startDirectPlayback() {
        retry.visibility = View.GONE
        status.setText(R.string.native_playback_starting)
        runCatching {
            when (request.kind) {
                PlaybackKind.LIVE -> {
                    val livePlayer = application.container.livePlayerFactory.create(activity, false)
                    livePlayer.prepare(
                        request.uri,
                        LivePlaybackOptions(forceRtpTcp = preferRtpTcp, videoOnly = false),
                    )
                    livePlayer.player
                }
                PlaybackKind.RECORDED -> RecordedPlayerFactory.create(
                    activity,
                    application.container.httpClient,
                    preferSoftwareVideoDecoder = false,
                ).apply {
                    val item = MediaItem.Builder().setUri(request.uri).apply {
                        val path = request.uri.substringBefore('?').lowercase()
                        when {
                            path.endsWith(".m3u8") -> setMimeType("application/x-mpegURL")
                            path.endsWith(".mp4") -> setMimeType("video/mp4")
                        }
                    }.build()
                    setMediaItem(item)
                    prepare()
                }
            }
        }.onSuccess(::attachPlayer).onFailure {
            status.setText(R.string.native_playback_start_failed)
        }
    }

    private fun startDocumentationPlayback() {
        status.text = request.detail ?: if (request.kind == PlaybackKind.LIVE) "Live" else "Recording"
        if (request.kind == PlaybackKind.LIVE) {
            pictureInPictureReady = true
            pictureInPicture.isEnabled = true
            pictureInPicture.alpha = 1f
        } else {
            updateDocumentationProgress()
        }
        updatePlayPauseControl(true)
    }

    private fun startCompatibilityPlayback(rawRequestId: String) {
        retry.visibility = View.GONE
        status.text = if (request.compatibilityTest) {
            "Checking camera compatibility automatically"
        } else {
            "Finding a compatible stream"
        }
        liveJob = activity.lifecycleScope.launch {
            val target = PlayerViewMedia3VideoOutputTarget(
                playerView = playerView,
                onAttached = { attached -> attachPlayer(attached) },
                onDetached = { detached -> if (player === detached) detachPlayer() },
            )
            val session = try {
                application.container.singleLivePlaybackCoordinator.open(
                    SingleLivePlaybackRequestId(rawRequestId),
                    target,
                    videoOnly = request.compatibilityTestVideoOnly,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status.setText(R.string.native_live_start_failed)
                return@launch
            }
            try {
                session.snapshots.collect { snapshot ->
                    val state = snapshot.state
                    status.text = when (state) {
                        is PlaybackCompatibilityState.Attempting -> "Trying a playback option"
                        is PlaybackCompatibilityState.Recovering -> "Trying the next playback option"
                        is PlaybackCompatibilityState.Verified,
                        is PlaybackCompatibilityState.UnpersistedLive,
                        is PlaybackCompatibilityState.Persisting,
                        -> "Playing with a verified camera setting"
                        is PlaybackCompatibilityState.Finished -> when (state.result) {
                            is PlaybackResult.VerifiedLive,
                            is PlaybackResult.UnpersistedLive,
                            -> "Playing with a verified camera setting"
                            is PlaybackResult.Cancelled -> "Compatibility check stopped"
                            else -> playbackFailureSummary(state.result) +
                                "\nCleanup: ${snapshot.diagnosticReleaseStage}" +
                                (snapshot.diagnosticPriorFailure?.let {
                                    "\nPrior: ${it.category.name} / ${it.phase.name}" +
                                        "\nCode: ${it.diagnosticCode.name}"
                                } ?: "\nPrior: not captured")
                        }
                        is PlaybackCompatibilityState.Idle -> "Preparing compatibility check"
                        is PlaybackCompatibilityState.Releasing,
                        is PlaybackCompatibilityState.ReleasedAwaitingPersistence,
                        -> "Saving the camera setting"
                        is PlaybackCompatibilityState.PresentingSnapshot -> "Confirming video output"
                    }
                    if (request.compatibilityTest) {
                        nativeCompatibilityTestStatus(request.title, state)?.let(onCompatibilityTestStatus)
                    }
                    if (
                        request.compatibilityTest &&
                        nativeCompatibilityTestComplete(state) &&
                        !compatibilityTestExitScheduled
                    ) {
                        compatibilityTestExitScheduled = true
                        delay(COMPATIBILITY_TEST_RESULT_DISPLAY_MILLIS)
                        onClose()
                    }
                }
            } finally {
                withContext(NonCancellable) { session.close() }
                detachPlayer()
            }
        }
    }

    private fun attachPlayer(attached: ExoPlayer) {
        if (player !== attached) {
            if (!coordinatorOwned) player?.release()
            player = attached
        }
        playerView.player = attached
        attached.volume = if (muted) 0f else 1f
        attached.playWhenReady = true
        attached.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayPauseControl(attached.isPlaying)
                when (playbackState) {
                    Player.STATE_IDLE -> Unit
                    Player.STATE_BUFFERING -> status.setText(R.string.native_playback_loading)
                    Player.STATE_READY -> if (!request.compatibilityTest) {
                        status.text = request.detail ?: if (request.kind == PlaybackKind.LIVE) "Live" else "Recording"
                        if (request.kind == PlaybackKind.LIVE) {
                            pictureInPictureReady = true
                            pictureInPicture.isEnabled = true
                            pictureInPicture.alpha = 1f
                        }
                    }
                    Player.STATE_ENDED -> {
                        status.setText(R.string.native_playback_finished)
                        showChrome(requestFocus = false)
                        if (!playbackEndedReported) {
                            playbackEndedReported = true
                            onPlaybackEnded()
                        }
                    }
                }
                updateDiagnostics()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseControl(isPlaying)
                if (isPlaying) scheduleChromeHide() else showChrome(requestFocus = false)
            }

            override fun onPlayerError(error: PlaybackException) {
                status.setText(R.string.native_playback_failed)
                retry.visibility = if (coordinatorOwned) View.GONE else View.VISIBLE
                updateDiagnostics()
                showChrome(requestFocus = false)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                updateDiagnostics()
            }
        })
        startProgressUpdates(attached)
        updateDiagnostics()
    }

    private fun detachPlayer() {
        progressJob?.cancel()
        progressJob = null
        playerView.player = null
        player = null
    }

    private fun seekBy(offsetMillis: Long) {
        if (request.kind != PlaybackKind.RECORDED) return
        if (documentationPlayback) {
            documentationPositionMillis = boundedSeekPosition(
                documentationPositionMillis,
                documentationDurationMillis,
                offsetMillis,
            )
            updateDocumentationProgress()
            return
        }
        val active = player ?: return
        val duration = active.duration.takeIf { it > 0L }
        if (!active.isCurrentMediaItemSeekable || duration == null) {
            status.setText(R.string.native_playback_seek_unavailable)
            showChrome(requestFocus = false)
            return
        }
        active.seekTo(boundedSeekPosition(active.currentPosition, duration, offsetMillis))
        updateProgress(active)
    }

    private fun updateDocumentationProgress() {
        progress.text = activity.getString(
            R.string.native_playback_progress,
            playbackTime(documentationPositionMillis),
            playbackTime(documentationDurationMillis),
        )
        timeline.progress = (
            documentationPositionMillis.toDouble() / documentationDurationMillis.toDouble() * timeline.max
            ).toInt().coerceIn(0, timeline.max)
        progressStrip.contentDescription =
            "Playback timeline, ${progress.text}, use left and right to seek 10 seconds"
    }

    private fun startProgressUpdates(active: ExoPlayer) {
        progressJob?.cancel()
        if (request.kind != PlaybackKind.RECORDED) return
        progressJob = activity.lifecycleScope.launch {
            while (player === active) {
                updateProgress(active)
                delay(500)
            }
        }
    }

    private fun updateProgress(active: Player) {
        recordAutomaticReviewWatch(active)
        val position = active.currentPosition.coerceAtLeast(0L)
        val duration = active.duration.takeIf { it > 0 }
        val seekable = active.isCurrentMediaItemSeekable && duration != null
        progressStrip.isEnabled = seekable
        progressStrip.alpha = if (seekable) 1f else 0.55f
        progress.text = if (duration != null) {
            "${playbackTime(position)} / ${playbackTime(duration)}"
        } else {
            playbackTime(position)
        }
        timeline.progress = if (duration != null) {
            ((position.toDouble() / duration.toDouble()) * timeline.max)
                .toInt()
                .coerceIn(0, timeline.max)
        } else {
            0
        }
        progressStrip.contentDescription = if (duration != null) {
            "Playback timeline, ${playbackTime(position)} of ${playbackTime(duration)}, " +
                if (seekable) "use left and right to seek 10 seconds" else "loading"
        } else {
            "Playback timeline loading"
        }
    }

    private fun recordAutomaticReviewWatch(active: Player) {
        val now = SystemClock.elapsedRealtime()
        if (active.isPlaying && active.playbackState == Player.STATE_READY) {
            lastWatchSampleElapsedRealtime?.let { previous ->
                watchedPlaybackMillis += (now - previous).coerceIn(0L, 1_000L)
            }
            lastWatchSampleElapsedRealtime = now
        } else {
            lastWatchSampleElapsedRealtime = null
        }
        val duration = active.duration.takeIf { it > 0L }
        if (
            !automaticReviewThresholdReported &&
            request.activityItemId != null &&
            watchedEnoughForAutomaticReview(watchedPlaybackMillis, duration)
        ) {
            automaticReviewThresholdReported = true
            onAutomaticReviewWatchThresholdReached()
        }
    }

    private fun playbackTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun retryPlayback() {
        if (coordinatorOwned) return
        progressJob?.cancel()
        progressJob = null
        playerView.player = null
        player?.release()
        player = null
        playbackEndedReported = false
        startDirectPlayback()
    }

    private fun togglePlayback() {
        val active = player ?: return
        if (active.isPlaying) {
            active.pause()
        } else {
            if (active.playbackState == Player.STATE_ENDED) active.seekTo(0L)
            active.play()
        }
        updatePlayPauseControl(active.isPlaying)
        if (active.isPlaying) scheduleChromeHide() else showChrome(requestFocus = false)
    }

    private fun toggleAudio() {
        muted = !muted
        player?.volume = if (muted) 0f else 1f
        updateControlContent(
            view = audio,
            label = if (muted) "Muted" else "Audio",
            iconRes = if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on,
            accessibilityLabel = if (muted) "Turn on audio" else "Mute audio",
        )
        updateDiagnostics()
    }

    private fun updatePlayPauseControl(isPlaying: Boolean) {
        updateControlContent(
            view = playPause,
            label = if (isPlaying) "Pause" else "Play",
            iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            accessibilityLabel = if (isPlaying) "Pause playback" else "Resume playback",
        )
    }

    private fun updateControlContent(
        view: TextView,
        label: String,
        iconRes: Int,
        accessibilityLabel: String,
    ) {
        view.text = ""
        view.contentDescription = accessibilityLabel
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        view.compoundDrawablesRelative.forEach { drawable -> drawable?.setTint(Color.WHITE) }
        if (view.hasFocus()) controlHint.text = accessibilityLabel
    }

    private fun View.isAncestorOf(descendant: View): Boolean {
        var parent = descendant.parent
        while (parent is View) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }

    private fun LinearLayout.firstEnabledFocusableChild(): View? =
        (0 until childCount)
            .map(::getChildAt)
            .firstOrNull { it.isEnabled && it.isFocusable && it.isVisible }

    private fun updateDiagnostics() {
        if (!diagnosticsEnabled) return
        val active = player
        val playbackState = when (active?.playbackState) {
            Player.STATE_BUFFERING -> "Buffering"
            Player.STATE_READY -> if (active.isPlaying) "Playing" else "Ready"
            Player.STATE_ENDED -> "Finished"
            else -> "Starting"
        }
        val video = active?.videoFormat
        val audioFormat = active?.audioFormat
        diagnostics.text = buildList {
            add("$playbackState • ${if (request.kind == PlaybackKind.LIVE) "Live" else "Recording"}")
            if (request.kind == PlaybackKind.LIVE) add(if (preferRtpTcp) "Reliable TCP transport preferred" else "Automatic transport")
            video?.let { format ->
                val size = if (format.width > 0 && format.height > 0) "${format.width} × ${format.height}" else "Size pending"
                add("Video: ${format.sampleMimeType ?: "unknown"} • $size")
            }
            audioFormat?.let { format -> add("Audio: ${format.sampleMimeType ?: "unknown"}${if (muted) " • muted" else ""}") }
        }.joinToString("\n")
    }

    private fun enterPictureInPicture() {
        if (!pictureInPictureReady) return
        val videoSize = player?.videoSize
        val sourceRect = Rect().takeIf { playerView.getGlobalVisibleRect(it) }
        setPictureInPictureActive(true)
        val entered = onEnterPictureInPicture(
            PictureInPictureRequest(
                title = request.title,
                subtitle = request.detail ?: "Live",
                aspectRatio = videoSize?.let { pipAspectRatio(it.width, it.height) },
                sourceRectHint = sourceRect,
            ),
        )
        if (!entered) {
            setPictureInPictureActive(false)
            status.setText(R.string.native_picture_in_picture_unavailable)
        }
    }
}

internal fun nativeCompatibilityTestComplete(state: PlaybackCompatibilityState): Boolean = when (state) {
    is PlaybackCompatibilityState.Verified,
    is PlaybackCompatibilityState.UnpersistedLive,
    -> true
    is PlaybackCompatibilityState.Finished -> nativeCompatibilityTestResultComplete(state.result)
    else -> false
}

internal fun nativeCompatibilityTestResultComplete(result: PlaybackResult): Boolean =
    result !is PlaybackResult.Cancelled

internal fun nativeCompatibilityTestStatus(
    cameraName: String,
    state: PlaybackCompatibilityState,
): String? = when (state) {
    is PlaybackCompatibilityState.Attempting ->
        "$cameraName: trying choice ${state.run.attemptsStarted.coerceAtLeast(1)} of " +
            "${state.plan.budget.maxAttempts + state.plan.budget.maxRecoveryCycles} • " +
            nativeCompatibilityChoiceLabel(state.attempt.candidate)
    is PlaybackCompatibilityState.Recovering ->
        "$cameraName: reconnecting before the next safe choice"
    is PlaybackCompatibilityState.Verified ->
        "$cameraName: choice saved • ${nativeCompatibilityChoiceLabel(state.result.strategy.candidate)}"
    is PlaybackCompatibilityState.UnpersistedLive ->
        "$cameraName: video works for now, but the choice could not be saved"
    is PlaybackCompatibilityState.Persisting ->
        "$cameraName: video works • saving ${nativeCompatibilityChoiceLabel(state.strategy.candidate)}"
    is PlaybackCompatibilityState.Finished -> when (val result = state.result) {
        is PlaybackResult.VerifiedLive ->
            "$cameraName: choice saved • ${nativeCompatibilityChoiceLabel(result.strategy.candidate)}"
        is PlaybackResult.UnpersistedLive ->
            "$cameraName: video works for now, but the choice could not be saved"
        is PlaybackResult.Cancelled -> null
        else -> "$cameraName: Opah couldn't confirm a new choice. Existing playback settings were not changed"
    }
    is PlaybackCompatibilityState.Idle -> "$cameraName: preparing the first safe choice"
    is PlaybackCompatibilityState.Releasing -> "$cameraName: moving to the next safe choice"
    is PlaybackCompatibilityState.ReleasedAwaitingPersistence -> "$cameraName: saving the working choice"
    is PlaybackCompatibilityState.PresentingSnapshot -> "$cameraName: preparing video"
}

private fun nativeCompatibilityChoiceLabel(candidate: PlaybackCandidate): String = buildList {
    add(if (candidate.transportMode == TransportMode.FORCE_RTP_TCP) "more reliable connection" else "standard connection")
    add(if (candidate.audioMode == AudioMode.VIDEO_ONLY) "video only" else "video and sound")
    add(
        when (candidate.decoderMode) {
            DecoderMode.PLATFORM_DEFAULT -> "automatic video decoder"
            DecoderMode.PREFER_HARDWARE -> "TV video decoder"
            DecoderMode.ALLOW_SOFTWARE -> "compatibility video decoder"
        },
    )
}.joinToString(" • ")

private const val COMPATIBILITY_TEST_RESULT_DISPLAY_MILLIS = 1_200L
