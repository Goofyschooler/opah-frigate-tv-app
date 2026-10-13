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
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
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

/** Immutable appearance for surfaces that can be shown while the foreground UI is absent. */
internal data class NativeThemeSnapshot(
    val palette: NativePalette,
    val subtleRoundedCorners: Boolean,
    val reducedMotion: Boolean,
    val reducedTransparency: Boolean,
    val highContrast: Boolean,
)

internal object NativeTheme {
    var palette: NativePalette = darkPalette()
        private set
    var subtleRoundedCorners: Boolean = false
        private set
    var reducedMotion: Boolean = false
        private set
    var reducedTransparency: Boolean = false
        private set
    var highContrast: Boolean = false
        private set

    fun snapshot(context: Context, settings: AppSettings): NativeThemeSnapshot {
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
                text = readableForeground(palette.background, palette.panel, palette.panelSelected),
                secondaryText = readableForeground(palette.background, palette.panel, palette.panelSelected),
                divider = blend(palette.divider, palette.text, 0.35f),
            ) else palette
        }
        return NativeThemeSnapshot(
            next, settings.subtleRoundedCorners, settings.reducedMotion,
            settings.reducedTransparency, settings.highContrast,
        )
    }

    fun update(context: Context, settings: AppSettings): Boolean {
        val next = snapshot(context, settings).palette
        val changed = next != palette || subtleRoundedCorners != settings.subtleRoundedCorners ||
            reducedMotion != settings.reducedMotion || reducedTransparency != settings.reducedTransparency ||
            highContrast != settings.highContrast
        palette = next
        subtleRoundedCorners = settings.subtleRoundedCorners
        reducedMotion = settings.reducedMotion
        reducedTransparency = settings.reducedTransparency
        highContrast = settings.highContrast
        return changed
    }

    private fun darkPalette() = NativePalette(
        background = 0xFF0C1018.toInt(),
        panel = 0xFF202733.toInt(),
        panelSelected = 0xFF35404F.toInt(),
        focus = 0xFFFF7048.toInt(),
        text = 0xFFF7F8FC.toInt(),
        secondaryText = 0xFFBFC8D6.toInt(),
        divider = 0xFF424D5F.toInt(),
        activityBadge = 0xFF57D5FF.toInt(),
        activityBadgeText = 0xFF06131C.toInt(),
    )

    private fun lightPalette() = NativePalette(
        background = 0xFFE8EDF3.toInt(),
        panel = Color.WHITE,
        panelSelected = 0xFFD6E0ED.toInt(),
        focus = 0xFFD94B25.toInt(),
        text = 0xFF17202A.toInt(),
        secondaryText = 0xFF4C5968.toInt(),
        divider = 0xFFC7D0DA.toInt(),
        activityBadge = 0xFF007A9E.toInt(),
        activityBadgeText = Color.WHITE,
    )

    private fun customPalette(background: Int, accent: Int): NativePalette {
        val base = opaque(background)
        val text = readableForeground(base)
        val dark = text == Color.WHITE
        // Keep the stored colors intact; derive readable surfaces and foregrounds from them.
        fun readableSurface(candidate: Int): Int {
            var result = candidate
            repeat(12) {
                if (ColorUtils.calculateContrast(text, result) >= 4.5) return result
                result = blend(result, if (dark) Color.BLACK else Color.WHITE, 0.08f)
            }
            return result
        }
        val panel = readableSurface(blend(base, if (dark) Color.WHITE else Color.BLACK, if (dark) 0.07f else 0.045f))
        val selected = readableSurface(blend(panel, opaque(accent), if (dark) 0.18f else 0.12f))
        var secondary = blend(text, base, 0.28f)
        repeat(8) {
            if (listOf(base, panel, selected).any { ColorUtils.calculateContrast(secondary, it) < 4.5 }) {
                secondary = blend(secondary, text, 0.3f)
            }
        }
        if (listOf(base, panel, selected).any { ColorUtils.calculateContrast(secondary, it) < 4.5 }) secondary = text
        val cyan = if (dark) 0xFF57D5FF.toInt() else 0xFF007A9E.toInt()
        val amber = if (dark) 0xFFFFC857.toInt() else 0xFF9A5B00.toInt()
        val activityBadge = if (colorDistance(accent, cyan) < 150) amber else cyan
        return NativePalette(
            background = base,
            panel = panel,
            panelSelected = selected,
            focus = opaque(accent),
            text = text,
            secondaryText = secondary,
            divider = blend(panel, text, if (dark) 0.18f else 0.15f),
            activityBadge = activityBadge,
            activityBadgeText = if (isDark(activityBadge)) Color.WHITE else 0xFF06131C.toInt(),
        )
    }

    private fun opaque(color: Int): Int = color or 0xFF000000.toInt()

    private fun readableForeground(vararg surfaces: Int): Int = listOf(Color.WHITE, Color.BLACK)
        .maxBy { text -> surfaces.minOf { ColorUtils.calculateContrast(text, opaque(it)) } }

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

/** Shared focus states keep the active destination distinct from remote focus. */
internal fun Context.nativeFlatFocusableBackground(): StateListDrawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), nativeUiShape(NativeTheme.palette.panelSelected))
    addState(
        intArrayOf(android.R.attr.state_focused),
        nativeUiShape(NativeTheme.palette.panelSelected).also {
            it.setStroke(dp(2), NativeTheme.palette.text)
        },
    )
    addState(
        intArrayOf(android.R.attr.state_selected),
        nativeUiShape(NativeTheme.palette.panelSelected).also {
            it.setStroke(dp(1), NativeTheme.palette.focus)
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
            it.setStroke(dp(3), NativeTheme.palette.text)
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
            it.setStroke(dp(2), NativeTheme.palette.text)
        },
    )
    addState(
        intArrayOf(),
        nativeUiShape(blendForReading(NativeTheme.palette.panel, NativeTheme.palette.background)).also {
            it.setStroke(dp(1), NativeTheme.palette.divider)
        },
    )
}

internal fun Context.nativeFlatScrollRegionBackground(): StateListDrawable = StateListDrawable().apply {
    addState(
        intArrayOf(android.R.attr.state_focused),
        nativeUiShape(Color.TRANSPARENT).also {
            it.setStroke(dp(2), NativeTheme.palette.text)
        },
    )
    addState(intArrayOf(), nativeUiShape(Color.TRANSPARENT))
}

internal fun Context.nativeUiShape(fillColor: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(fillColor)
    cornerRadius = dp(if (NativeTheme.subtleRoundedCorners) 16 else 12).toFloat()
}

/** Tint and highlights provide depth without copying decoded video into the UI renderer. */
internal fun Context.nativeGlassPanelBackground(strong: Boolean = false): Drawable {
    return nativeGlassPanelBackground(
        NativeThemeSnapshot(
            NativeTheme.palette, NativeTheme.subtleRoundedCorners, NativeTheme.reducedMotion,
            NativeTheme.reducedTransparency, NativeTheme.highContrast,
        ),
        strong,
    )
}

internal fun Context.nativeGlassPanelBackground(theme: NativeThemeSnapshot, strong: Boolean = false): Drawable {
    val palette = theme.palette
    val opaque = strong || theme.reducedTransparency || theme.highContrast
    val alpha = if (opaque) 255 else 232
    return GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(palette.panel, palette.text, 0.08f), alpha),
            ColorUtils.setAlphaComponent(palette.panel, alpha),
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(palette.panel, palette.background, 0.18f), alpha),
        ),
    ).apply {
        cornerRadius = dp(if (theme.subtleRoundedCorners) 20 else 16).toFloat()
        setStroke(dp(1), ColorUtils.setAlphaComponent(palette.text, if (theme.highContrast) 160 else 42))
    }
}

internal fun Context.nativeGlassOverlayBackground(): Drawable = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    intArrayOf(
        if (NativeTheme.reducedTransparency || NativeTheme.highContrast) 0xFF202733.toInt() else 0xED293342.toInt(),
        if (NativeTheme.reducedTransparency || NativeTheme.highContrast) 0xFF141C27.toInt() else 0xED141C27.toInt(),
    ),
).apply {
    cornerRadius = dp(16).toFloat()
    setStroke(dp(1), 0x55FFFFFF)
}

internal fun Context.nativeCanvasBackground(): Drawable = GradientDrawable(
    GradientDrawable.Orientation.TL_BR,
    intArrayOf(
        ColorUtils.blendARGB(NativeTheme.palette.background, NativeTheme.palette.focus, 0.035f),
        NativeTheme.palette.background,
        ColorUtils.blendARGB(NativeTheme.palette.background, NativeTheme.palette.panel, 0.12f),
    ),
)

internal fun View.applyNativeFocusMotion(focused: Boolean, scale: Float = 1.025f) {
    animate().cancel()
    val target = if (focused && !NativeTheme.reducedMotion) scale else 1f
    if (NativeTheme.reducedMotion) {
        scaleX = target
        scaleY = target
    } else {
        animate().scaleX(target).scaleY(target).setDuration(120L)
            .setInterpolator(DecelerateInterpolator()).start()
    }
}

internal fun Context.nativeNavigationIcon(
    iconRes: Int,
    badgeText: String = "",
    centerDot: Boolean = false,
): Drawable {
    val icon = requireNotNull(getDrawable(iconRes)).mutate().apply {
        setTint(NativeTheme.palette.text)
    }
    return if (badgeText.isBlank() && !centerDot) {
        icon
    } else {
        NativeNavigationBadgeDrawable(this, icon, badgeText, centerDot)
    }
}

private class NativeNavigationBadgeDrawable(
    context: Context,
    private val icon: Drawable,
    private val badgeText: String,
    private val centerDot: Boolean,
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
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NativeTheme.palette.activityBadge
    }
    private val dotRadius = 3.5f * density

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val iconLeft = bounds.centerX() - iconSize / 2
        val iconTop = bounds.centerY() - iconSize / 2
        icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
    }

    override fun draw(canvas: Canvas) {
        icon.draw(canvas)
        if (badgeText.isNotBlank()) {
            canvas.drawText(
                badgeText,
                bounds.exactCenterX(),
                bounds.exactCenterY() + textCenterOffset,
                textPaint,
            )
        } else if (centerDot) {
            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), dotRadius, dotPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        icon.alpha = alpha
        textPaint.alpha = alpha
        dotPaint.alpha = alpha
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
            nativeUiShape(0xFF435268.toInt()).also {
                it.setStroke(dp(2), Color.WHITE)
            },
        )
        addState(
            intArrayOf(android.R.attr.state_selected),
            nativeUiShape(0xCC1B2735.toInt()).also {
                it.setStroke(dp(1), NativeTheme.palette.focus)
            },
        )
        addState(intArrayOf(), nativeUiShape(if (NativeTheme.reducedTransparency) 0xFF202733.toInt() else 0x99202733.toInt()))
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
