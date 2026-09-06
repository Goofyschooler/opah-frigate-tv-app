package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.opah.tv.R
import app.opah.tv.data.model.CameraPtzInfo
import app.opah.tv.data.network.PtzCommand
import app.opah.tv.data.network.PtzConnectionState
import app.opah.tv.data.network.PtzConnectionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@SuppressLint("ViewConstructor", "SetTextI18n", "ClickableViewAccessibility")
internal class NativePtzSurface(
    private val activity: ComponentActivity,
    cameraTitle: String,
    private val cameraName: String,
    private val info: CameraPtzInfo,
    initialConnection: PtzConnectionState,
    initialError: String?,
    cachedBitmap: Bitmap?,
    refreshBitmap: suspend () -> Bitmap?,
    private val onCommand: (PtzCommand) -> Unit,
    private val onRetry: () -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(activity) {
    private val status = TextView(activity).apply {
        textSize = 14f
        setTextColor(NativeTheme.palette.secondaryText)
    }
    private var imageJob: Job? = null

    init {
        orientation = VERTICAL
        setPadding(activity.dp(20), activity.dp(16), activity.dp(20), activity.dp(18))
        setBackgroundColor(NativeTheme.palette.background)
        addView(
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    LinearLayout(activity).apply {
                        orientation = VERTICAL
                        addView(TextView(activity).apply {
                            text = cameraTitle
                            textSize = 24f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(NativeTheme.palette.text)
                        })
                        addView(status)
                    },
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(controlButton("Close", R.drawable.ic_chevron_left, onClose))
            },
        )
        val preview = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(NativeTheme.palette.panel)
            setImageBitmap(cachedBitmap)
        }
        val controlPanel = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(18))
            if (info.canMove) {
                addView(TextView(activity).apply {
                    text = "Move camera"
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(NativeTheme.palette.text)
                })
                addView(
                    GridLayout(activity).apply {
                        columnCount = 3
                        addView(spacer())
                        addView(holdButton("↑", "Move up", PtzCommand.MoveUp))
                        addView(spacer())
                        addView(holdButton("←", "Move left", PtzCommand.MoveLeft))
                        addView(holdButton("■", "Stop moving", PtzCommand.Stop))
                        addView(holdButton("→", "Move right", PtzCommand.MoveRight))
                        addView(spacer())
                        addView(holdButton("↓", "Move down", PtzCommand.MoveDown))
                        addView(spacer())
                    },
                )
            }
            if (info.canZoom) {
                addView(controlRow("Zoom", PtzCommand.ZoomOut, PtzCommand.ZoomIn))
            }
            if (info.canFocus) {
                addView(controlRow("Focus", PtzCommand.FocusOut, PtzCommand.FocusIn))
            }
            if (info.presets.isNotEmpty()) {
                addView(TextView(activity).apply {
                    text = "Presets"
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(NativeTheme.palette.text)
                    setPadding(0, activity.dp(10), 0, activity.dp(4))
                })
                info.presets.take(8).forEach { preset ->
                    addView(actionRow(preset) { onCommand(PtzCommand.Preset(preset)) })
                }
            }
            addView(actionRow("Reconnect controls", onRetry).apply { tag = "retry" })
        }
        val controlScroller = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(NativeTheme.palette.panel)
            addView(controlPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(preview, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = activity.dp(12) })
                addView(
                    View(activity).apply { setBackgroundColor(NativeTheme.palette.divider) },
                    LayoutParams(activity.dp(1), LayoutParams.MATCH_PARENT).apply { marginEnd = activity.dp(12) },
                )
                addView(controlScroller, LayoutParams(activity.dp(340), LayoutParams.MATCH_PARENT))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = activity.dp(10) },
        )
        update(initialConnection, initialError)
        if (cachedBitmap == null) {
            imageJob = activity.lifecycleScope.launch { refreshBitmap()?.let(preview::setImageBitmap) }
        }
        controlPanel.post { controlPanel.focusSearch(View.FOCUS_DOWN)?.requestFocus() }
    }

    fun update(connection: PtzConnectionState, error: String?) {
        status.text = error ?: when (connection.status) {
            PtzConnectionStatus.CONNECTED -> "Camera controls connected"
            PtzConnectionStatus.CONNECTING -> "Connecting camera controls"
            PtzConnectionStatus.FAILED -> connection.message ?: "Camera controls could not connect"
            PtzConnectionStatus.DISCONNECTED -> "Camera controls disconnected"
        }
    }

    fun close() { imageJob?.cancel(); onCommand(PtzCommand.Stop) }

    private fun controlRow(label: String, decrease: PtzCommand, increase: PtzCommand) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, activity.dp(7), 0, 0)
        addView(TextView(activity).apply {
            text = label
            textSize = 15f
            setTextColor(NativeTheme.palette.secondaryText)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(holdButton("−", "$label out", decrease))
        addView(holdButton("+", "$label in", increase))
    }

    private fun holdButton(label: String, description: String, command: PtzCommand) =
        controlButton(label, action = { Unit }).apply {
        contentDescription = description
        layoutParams = LayoutParams(activity.dp(72), activity.dp(40)).apply {
            marginStart = activity.dp(2)
            marginEnd = activity.dp(2)
        }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> onCommand(command)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onCommand(PtzCommand.Stop)
            }
            false
        }
        setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) return@setOnKeyListener false
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) onCommand(command)
                KeyEvent.ACTION_UP -> onCommand(PtzCommand.Stop)
            }
            true
        }
    }

    private fun controlButton(
        label: String,
        iconRes: Int? = null,
        action: () -> Unit,
    ) = TextView(activity).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(NativeTheme.palette.text)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        isFocusable = true
        isClickable = true
        background = activity.nativeFlatFocusableBackground()
        iconRes?.let { resource ->
            setCompoundDrawablesRelativeWithIntrinsicBounds(resource, 0, 0, 0)
            compoundDrawablePadding = activity.dp(5)
            compoundDrawablesRelative.forEach { drawable -> drawable?.setTint(NativeTheme.palette.text) }
        }
        setPadding(activity.dp(10), activity.dp(5), activity.dp(10), activity.dp(5))
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, activity.dp(40)).apply {
            marginStart = activity.dp(2)
            marginEnd = activity.dp(2)
        }
        setOnClickListener { action() }
    }

    private fun actionRow(label: String, action: () -> Unit) = TextView(activity).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(NativeTheme.palette.text)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        isFocusable = true
        isClickable = true
        background = activity.nativeFlatFocusableBackground()
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_right, 0)
        compoundDrawablesRelative.forEach { drawable -> drawable?.setTint(NativeTheme.palette.secondaryText) }
        setPadding(activity.dp(10), activity.dp(5), activity.dp(8), activity.dp(5))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(40))
        setOnClickListener { action() }
    }

    private fun spacer() = View(activity).apply {
        layoutParams = GridLayout.LayoutParams().apply { width = activity.dp(76); height = activity.dp(40) }
    }
}
