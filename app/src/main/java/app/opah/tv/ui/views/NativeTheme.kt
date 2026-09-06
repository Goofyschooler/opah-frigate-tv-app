package app.opah.tv.ui.views

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import app.opah.tv.data.model.AppSettings
import app.opah.tv.data.model.AppearanceMode
import kotlin.math.roundToInt

internal data class NativePalette(
    val background: Int,
    val panel: Int,
    val panelSelected: Int,
    val focus: Int,
    val text: Int,
    val secondaryText: Int,
    val divider: Int,
    val activityBadge: Int,
    val activityBadgeText: Int,
)

internal object NativeTheme {
    var palette: NativePalette = darkPalette()
        private set
    var subtleRoundedCorners: Boolean = false
        private set

    fun update(context: Context, settings: AppSettings): Boolean {
        val next = when (settings.appearanceMode) {
            AppearanceMode.SYSTEM -> if (
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            ) darkPalette() else lightPalette()
            AppearanceMode.DARK -> darkPalette()
            AppearanceMode.LIGHT -> lightPalette()
            AppearanceMode.CUSTOM -> customPalette(
                background = settings.customThemeColors.backgroundArgb,
                accent = settings.customThemeColors.accentArgb,
            )
        }.let { palette ->
            if (settings.highContrast) palette.copy(
                text = if (isDark(palette.background)) Color.WHITE else Color.BLACK,
                secondaryText = if (isDark(palette.background)) 0xFFE4EAF1.toInt() else 0xFF24303D.toInt(),
                divider = blend(palette.divider, palette.text, 0.35f),
            ) else palette
        }
        val changed = next != palette || subtleRoundedCorners != settings.subtleRoundedCorners
        palette = next
        subtleRoundedCorners = settings.subtleRoundedCorners
        return changed
    }

    private fun darkPalette() = NativePalette(
        background = 0xFF07111F.toInt(),
        panel = 0xFF101D2C.toInt(),
        panelSelected = 0xFF243247.toInt(),
        focus = 0xFFFF7048.toInt(),
        text = 0xFFFFF7F3.toInt(),
        secondaryText = 0xFFB9C4D2.toInt(),
        divider = 0xFF2A394C.toInt(),
        activityBadge = 0xFF57D5FF.toInt(),
        activityBadgeText = 0xFF06131C.toInt(),
    )

    private fun lightPalette() = NativePalette(
        background = 0xFFF2F4F7.toInt(),
        panel = Color.WHITE,
        panelSelected = 0xFFE2E8F0.toInt(),
        focus = 0xFFD94B25.toInt(),
        text = 0xFF17202A.toInt(),
        secondaryText = 0xFF4C5968.toInt(),
        divider = 0xFFC7D0DA.toInt(),
        activityBadge = 0xFF007A9E.toInt(),
        activityBadgeText = Color.WHITE,
    )

    private fun customPalette(background: Int, accent: Int): NativePalette {
        val dark = isDark(background)
        val text = if (dark) 0xFFFFFBF8.toInt() else 0xFF101820.toInt()
        val panel = blend(background, if (dark) Color.WHITE else Color.BLACK, if (dark) 0.07f else 0.045f)
        val cyan = if (dark) 0xFF57D5FF.toInt() else 0xFF007A9E.toInt()
        val amber = if (dark) 0xFFFFC857.toInt() else 0xFF9A5B00.toInt()
        val activityBadge = if (colorDistance(accent, cyan) < 150) amber else cyan
        return NativePalette(
            background = opaque(background),
            panel = panel,
            panelSelected = blend(panel, accent, if (dark) 0.18f else 0.12f),
            focus = opaque(accent),
            text = text,
            secondaryText = blend(text, background, 0.28f),
            divider = blend(panel, text, if (dark) 0.18f else 0.15f),
            activityBadge = activityBadge,
            activityBadgeText = if (isDark(activityBadge)) Color.WHITE else 0xFF06131C.toInt(),
        )
    }

    private fun opaque(color: Int): Int = color or 0xFF000000.toInt()

    private fun isDark(color: Int): Boolean {
        val luminance = (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0
        return luminance < 0.52
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        fun channel(first: Int, second: Int) = (first + (second - first) * amount).roundToInt().coerceIn(0, 255)
        return Color.rgb(
            channel(Color.red(from), Color.red(to)),
            channel(Color.green(from), Color.green(to)),
            channel(Color.blue(from), Color.blue(to)),
        )
    }

    private fun colorDistance(first: Int, second: Int): Int =
        kotlin.math.abs(Color.red(first) - Color.red(second)) +
            kotlin.math.abs(Color.green(first) - Color.green(second)) +
            kotlin.math.abs(Color.blue(first) - Color.blue(second))
}

/**
 * A focus treatment for dense, flat screens. Rows have no container at rest;
 * focus adds a filled outline and persistent selection uses an outline.
 */
internal fun Context.nativeFlatFocusableBackground(): StateListDrawable = StateListDrawable().apply {
    addState(
        intArrayOf(android.R.attr.state_focused),
        nativeUiShape(NativeTheme.palette.panelSelected).also {
            it.setStroke(dp(3), NativeTheme.palette.focus)
        },
    )
    addState(
        intArrayOf(android.R.attr.state_selected),
        nativeUiShape(Color.TRANSPARENT).also {
            it.setStroke(dp(2), NativeTheme.palette.focus)
        },
    )
    addState(intArrayOf(), nativeUiShape(Color.TRANSPARENT))
}

/**
 * A clearly bounded field treatment for TV text entry. The resting outline
 * keeps the field recognizable without bringing large rounded cards back.
 */
internal fun Context.nativeFlatFieldBackground(): StateListDrawable = StateListDrawable().apply {
    addState(
        intArrayOf(android.R.attr.state_focused),
        nativeUiShape(NativeTheme.palette.panelSelected).also {
            it.setStroke(dp(3), NativeTheme.palette.focus)
        },
    )
    addState(
        intArrayOf(),
        nativeUiShape(blendForReading(NativeTheme.palette.panel, NativeTheme.palette.background)).also {
            it.setStroke(dp(1), NativeTheme.palette.divider)
        },
    )
}

internal fun Context.nativeFlatReadingBackground(): StateListDrawable = StateListDrawable().apply {
    addState(
        intArrayOf(android.R.attr.state_focused),
        nativeUiShape(blendForReading(NativeTheme.palette.panel, NativeTheme.palette.background)).also {
            it.setStroke(dp(2), NativeTheme.palette.focus)
        },
    )
    addState(
        intArrayOf(),
        nativeUiShape(blendForReading(NativeTheme.palette.panel, NativeTheme.palette.background)).also {
            it.setStroke(dp(1), NativeTheme.palette.divider)
        },
    )
}

private fun Context.nativeUiShape(fillColor: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(fillColor)
    cornerRadius = if (NativeTheme.subtleRoundedCorners) dp(4).toFloat() else 0f
}

internal fun Context.nativeNavigationIcon(iconRes: Int, badgeText: String = ""): Drawable {
    val icon = requireNotNull(getDrawable(iconRes)).mutate().apply {
        setTint(NativeTheme.palette.text)
    }
    return if (badgeText.isBlank()) {
        icon
    } else {
        NativeNavigationBadgeDrawable(this, icon, badgeText)
    }
}

private class NativeNavigationBadgeDrawable(
    context: Context,
    private val icon: Drawable,
    private val badgeText: String,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val iconSize = (24f * density).toInt()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NativeTheme.palette.activityBadge
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            if (badgeText.length > 2) 7f else 9f,
            context.resources.displayMetrics,
        )
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val textCenterOffset = -(textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val iconLeft = bounds.centerX() - iconSize / 2
        val iconTop = bounds.centerY() - iconSize / 2
        icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
    }

    override fun draw(canvas: Canvas) {
        icon.draw(canvas)
        canvas.drawText(
            badgeText,
            bounds.exactCenterX(),
            bounds.exactCenterY() + textCenterOffset,
            textPaint,
        )
    }

    override fun setAlpha(alpha: Int) {
        icon.alpha = alpha
        textPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        icon.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in the Android framework")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = iconSize

    override fun getIntrinsicHeight(): Int = iconSize
}

internal fun Context.nativeFlatThumbnailBackground(): GradientDrawable =
    nativeUiShape(NativeTheme.palette.panelSelected).also {
        it.setStroke(dp(1), NativeTheme.palette.divider)
    }

internal fun Context.nativeFlatOverlayFocusableBackground(): StateListDrawable =
    StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            nativeUiShape(0xDD1B2735.toInt()).also {
                it.setStroke(dp(3), NativeTheme.palette.focus)
            },
        )
        addState(
            intArrayOf(android.R.attr.state_selected),
            nativeUiShape(0xCC1B2735.toInt()).also {
                it.setStroke(dp(1), NativeTheme.palette.focus)
            },
        )
        addState(intArrayOf(), nativeUiShape(0x88000000.toInt()))
    }

internal fun Context.nativeMotionRegionBackground(): StateListDrawable = StateListDrawable().apply {
    addState(
        intArrayOf(android.R.attr.state_focused),
        nativeUiShape(0x331B2735).also { it.setStroke(dp(3), NativeTheme.palette.focus) },
    )
    addState(
        intArrayOf(android.R.attr.state_selected),
        nativeUiShape((NativeTheme.palette.focus and 0x00FFFFFF) or 0x55000000).also {
            it.setStroke(dp(2), NativeTheme.palette.focus)
        },
    )
    addState(
        intArrayOf(),
        nativeUiShape(0x08000000).also { it.setStroke(dp(1), 0x66FFFFFF) },
    )
}

private fun blendForReading(first: Int, second: Int): Int = Color.rgb(
    (Color.red(first) * 3 + Color.red(second)) / 4,
    (Color.green(first) * 3 + Color.green(second)) / 4,
    (Color.blue(first) * 3 + Color.blue(second)) / 4,
)
