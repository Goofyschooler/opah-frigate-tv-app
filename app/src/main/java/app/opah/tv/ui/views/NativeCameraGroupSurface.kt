package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opah.tv.OpahApplication
import app.opah.tv.PictureInPictureRequest
import app.opah.tv.R
import app.opah.tv.pipAspectRatio
import app.opah.tv.playback.LivePlaybackOptions
import app.opah.tv.playback.LivePlayer
import app.opah.tv.ui.CameraGroupViewUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
@SuppressLint("ViewConstructor", "SetTextI18n")
internal class NativeCameraGroupSurface(
    private val activity: ComponentActivity,
    private val state: CameraGroupViewUiState,
    private val preferRtpTcp: Boolean,
    private val cachedBitmap: (String) -> Bitmap?,
    private val refreshBitmap: suspend (String, Int) -> Bitmap?,
    private val pictureInPictureAvailable: Boolean,
    private val onEnterPictureInPicture: (PictureInPictureRequest) -> Boolean,
    private val onStartMonitor: () -> Unit,
    private val onBack: () -> Unit,
) : FrameLayout(activity) {
    private val application = activity.application as OpahApplication
    private val players = mutableListOf<LivePlayer>()
    private val jobs = mutableListOf<Job>()
    private val readyCameraNames = linkedSetOf<String>()
    private val cameraLabels = mutableListOf<View>()
    private val headerView: View
    private val controlsView: LinearLayout
    private var warningView: View? = null
    private var pictureInPictureButton: TextView? = null
    private var chromeHideJob: Job? = null
    private var chromeVisible = true
    private var pictureInPictureActive = false

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        addView(buildCameraLayout(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        headerView = buildHeader()
        addView(headerView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = activity.dp(18)
            topMargin = activity.dp(16)
        })
        controlsView = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(6))
            setBackgroundColor(0xD9000000.toInt())
            addView(controlButton("Back", R.drawable.ic_chevron_left, onBack))
            addView(controlButton("Monitor mode", R.drawable.ic_videocam, onStartMonitor))
            if (state.streams.size == 2 && pictureInPictureAvailable) {
                pictureInPictureButton = controlButton(
                    "Picture-in-picture",
                    R.drawable.ic_picture_in_picture,
                    ::enterPictureInPicture,
                ).apply {
                    isEnabled = false
                    alpha = 0.45f
                }
                addView(pictureInPictureButton)
            }
        }
        addView(
            controlsView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        if (state.decoderWarning) {
            warningView = statusLabel("This TV may play fewer cameras more reliably")
            addView(
                warningView,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    topMargin = activity.dp(16)
                    rightMargin = activity.dp(18)
                },
            )
        }
        controlsView.post {
            controlsView.getChildAt(1)?.requestFocus()
            scheduleChromeHide()
        }
    }

    fun close() {
        chromeHideJob?.cancel()
        chromeHideJob = null
        jobs.forEach(Job::cancel)
        jobs.clear()
        players.forEach(LivePlayer::release)
        players.clear()
    }

    fun setPictureInPictureActive(active: Boolean) {
        pictureInPictureActive = active
        chromeHideJob?.cancel()
        if (!active) {
            pictureInPictureButton?.text = "Picture-in-picture"
            refreshPictureInPictureAvailability()
            showChrome(requestFocus = false)
        } else {
            hideChrome()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                onBack()
                return true
            }
            if (!chromeVisible && !pictureInPictureActive) {
                showChrome(requestFocus = true)
                return true
            }
            scheduleChromeHide()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildCameraLayout(): View = when (state.streams.size) {
        2 -> horizontalRow(tile(0), tile(1))
        3 -> horizontalRow(
            tile(0),
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tile(1), weightedVerticalParams())
                addView(tile(2), weightedVerticalParams())
            },
        )
        4 -> LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(horizontalRow(tile(0), tile(1)), weightedVerticalParams())
            addView(horizontalRow(tile(2), tile(3)), weightedVerticalParams())
        }
        else -> FrameLayout(activity).apply {
            addView(statusLabel("This camera group is unavailable"), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    private fun horizontalRow(first: View, second: View): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(first, weightedHorizontalParams())
        addView(second, weightedHorizontalParams())
    }

    private fun tile(index: Int): View {
        val stream = state.streams[index]
        return FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            val snapshot = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(NativeTheme.palette.panel)
                cachedBitmap(stream.camera.name)?.let(::setImageBitmap)
            }
            addView(snapshot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val playerView = PlayerView(activity).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                isFocusable = false
                visibility = View.INVISIBLE
                setBackgroundColor(Color.TRANSPARENT)
            }
            addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val cameraLabel = statusLabel(stream.camera.displayName)
            cameraLabel.visibility = View.GONE
            cameraLabels += cameraLabel
            addView(
                cameraLabel,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = activity.dp(10)
                    bottomMargin = activity.dp(10)
                },
            )
            if (cachedBitmap(stream.camera.name) == null) {
                jobs += activity.lifecycleScope.launch {
                    refreshBitmap(stream.camera.name, GROUP_SNAPSHOT_HEIGHT)?.let(snapshot::setImageBitmap)
                }
            }
            jobs += activity.lifecycleScope.launch {
                delay(index * PLAYER_START_STAGGER_MILLIS)
                val livePlayer = runCatching {
                    application.container.livePlayerFactory.create(activity, false).also { created ->
                        created.prepare(
                            stream.uri,
                            LivePlaybackOptions(forceRtpTcp = preferRtpTcp, videoOnly = true),
                        )
                    }
                }.getOrNull() ?: return@launch
                players += livePlayer
                playerView.player = livePlayer.player
                livePlayer.player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        playerView.visibility = View.VISIBLE
                        readyCameraNames += stream.camera.name
                        refreshPictureInPictureAvailability()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        playerView.visibility = View.INVISIBLE
                        readyCameraNames -= stream.camera.name
                        refreshPictureInPictureAvailability()
                    }
                })
            }
        }
    }

    private fun refreshPictureInPictureAvailability() {
        val enabled = shouldOfferNativeCameraGroupPictureInPicture(
            cameraCount = state.streams.size,
            pictureInPictureAvailable = pictureInPictureAvailable,
            readyCameraCount = readyCameraNames.size,
        )
        pictureInPictureButton?.isEnabled = enabled
        pictureInPictureButton?.alpha = if (enabled) 1f else 0.45f
    }

    private fun enterPictureInPicture() {
        if (
            !shouldOfferNativeCameraGroupPictureInPicture(
                cameraCount = state.streams.size,
                pictureInPictureAvailable = pictureInPictureAvailable,
                readyCameraCount = readyCameraNames.size,
            )
        ) return
        val sourceRect = Rect().takeIf { getGlobalVisibleRect(it) }
        setPictureInPictureActive(true)
        val entered = onEnterPictureInPicture(
            PictureInPictureRequest(
                title = state.title,
                subtitle = "${state.streams.size} cameras",
                aspectRatio = pipAspectRatio(16, 9),
                sourceRectHint = sourceRect,
            ),
        )
        if (!entered) {
            setPictureInPictureActive(false)
            pictureInPictureButton?.text = "Picture-in-picture unavailable"
        }
    }

    private fun buildHeader(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(10))
        setBackgroundColor(0xBB000000.toInt())
        addView(TextView(activity).apply {
            text = state.title
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(activity).apply {
            text = "${state.streams.size} live cameras"
            textSize = 14f
            setTextColor(0xFFD5DFEC.toInt())
        })
    }

    private fun statusLabel(label: String): TextView = TextView(activity).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        setPadding(activity.dp(10), activity.dp(7), activity.dp(10), activity.dp(7))
        setBackgroundColor(0xB3000000.toInt())
    }

    private fun controlButton(
        label: String,
        iconRes: Int,
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
        setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        compoundDrawablePadding = activity.dp(5)
        compoundDrawablesRelative.forEach { drawable -> drawable?.setTint(Color.WHITE) }
        setPadding(activity.dp(11), activity.dp(5), activity.dp(11), activity.dp(5))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(40)).apply {
            marginStart = activity.dp(2)
            marginEnd = activity.dp(2)
        }
        setOnClickListener {
            action()
            showChrome(requestFocus = false)
        }
    }

    private fun showChrome(requestFocus: Boolean) {
        if (pictureInPictureActive) return
        chromeVisible = true
        headerView.visibility = View.VISIBLE
        controlsView.visibility = View.VISIBLE
        warningView?.visibility = View.VISIBLE
        cameraLabels.forEach { it.visibility = View.GONE }
        if (requestFocus || findFocus() == null || findFocus() === this) {
            controlsView.getChildAt(1)?.requestFocus()
        }
        scheduleChromeHide()
    }

    private fun hideChrome() {
        chromeVisible = false
        headerView.visibility = View.GONE
        controlsView.visibility = View.GONE
        warningView?.visibility = View.GONE
        cameraLabels.forEach {
            it.visibility = if (pictureInPictureActive) View.GONE else View.VISIBLE
        }
        requestFocus()
    }

    private fun scheduleChromeHide() {
        chromeHideJob?.cancel()
        if (pictureInPictureActive) return
        chromeHideJob = activity.lifecycleScope.launch {
            delay(CHROME_HIDE_MILLIS)
            hideChrome()
        }
    }

    private fun weightedHorizontalParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
    private fun weightedVerticalParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

    private companion object {
        const val GROUP_SNAPSHOT_HEIGHT = 420
        const val PLAYER_START_STAGGER_MILLIS = 250L
        const val CHROME_HIDE_MILLIS = 5_000L
    }
}

internal fun shouldOfferNativeCameraGroupPictureInPicture(
    cameraCount: Int,
    pictureInPictureAvailable: Boolean,
    readyCameraCount: Int,
): Boolean = pictureInPictureAvailable && cameraCount == 2 && readyCameraCount >= cameraCount
