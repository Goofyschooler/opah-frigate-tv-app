package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.HslColor
import app.opah.tv.data.model.ThemeColorPolicy

internal data class NativeAppearanceUiState(
    val mode: AppearanceMode,
    val colors: CustomThemeColors,
    val reducedMotion: Boolean,
    val highContrast: Boolean,
    val subtleRoundedCorners: Boolean,
)

private data class ThemePreset(
    val name: String,
    val colors: CustomThemeColors,
)

@SuppressLint("ViewConstructor")
internal class NativeAppearanceSurface(
    private val activity: ComponentActivity,
    initialState: NativeAppearanceUiState,
    private val onMode: (AppearanceMode) -> Unit,
    private val onColors: (CustomThemeColors) -> Unit,
    private val onReducedMotion: (Boolean) -> Unit,
    private val onHighContrast: (Boolean) -> Unit,
    private val onSubtleRoundedCorners: (Boolean) -> Unit,
    private val onFocused: (String, View) -> Unit,
) : LinearLayout(activity) {
    private val preview = NativeThemePreviewView(activity)
    private val modeChoices = linkedMapOf<AppearanceMode, NativeThemeSwatchView>()
    private val presetChoices = linkedMapOf<String, NativeThemeSwatchView>()
    private val accentHue = track("Accent hue") { direction -> adjustAccentHue(direction) }
    private val accentStrength = track("Accent strength") { direction -> adjustAccentStrength(direction) }
    private val accentBrightness = track("Accent brightness") { direction -> adjustAccentBrightness(direction) }
    private val backgroundHue = track("Background hue") { direction -> adjustBackgroundHue(direction) }
    private val backgroundStrength = track("Background strength") { direction -> adjustBackgroundStrength(direction) }
    private val backgroundBrightness = track("Background brightness") { direction -> adjustBackgroundBrightness(direction) }
    private val reduceMotion = toggle("Reduce motion") { onReducedMotion(!state.reducedMotion) }
    private val highContrast = toggle("High contrast") { onHighContrast(!state.highContrast) }
    private val subtleCorners = toggle("Soft corners") {
        onSubtleRoundedCorners(!state.subtleRoundedCorners)
    }
    private var state = initialState

    private val presets = listOf(
        ThemePreset("Coral", CustomThemeColors(0xFFFF7048.toInt(), 0xFF07111F.toInt())),
        ThemePreset("Ocean", CustomThemeColors(0xFF53B7FF.toInt(), 0xFF071522.toInt())),
        ThemePreset("Forest", CustomThemeColors(0xFF65D68A.toInt(), 0xFF08170F.toInt())),
        ThemePreset("Plum", CustomThemeColors(0xFFD29BFF.toInt(), 0xFF180D20.toInt())),
        ThemePreset("Sunset", CustomThemeColors(0xFFFFB454.toInt(), 0xFF211108.toInt())),
        ThemePreset("Slate", CustomThemeColors(0xFF7DD8D2.toInt(), 0xFF11171A.toInt())),
    )

    init {
        orientation = VERTICAL
        setPadding(activity.dp(10), 0, 0, activity.dp(8))
        addView(label("Choose a look", 14f, bold = true))
        addView(
            LinearLayout(activity).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                AppearanceMode.entries.forEach { mode ->
                    val choice = NativeThemeSwatchView(activity).apply {
                        tag = "appearance:mode:${mode.name}"
                        isFocusable = true
                        isClickable = true
                        contentDescription = "${modeLabel(mode)} theme"
                        setOnClickListener { onMode(mode) }
                        onFocusChangeListener = focusListener(tag as String)
                    }
                    modeChoices[mode] = choice
                    addView(
                        choice,
                        LayoutParams(0, activity.dp(58), 1f).apply { marginEnd = activity.dp(5) },
                    )
                }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(4) },
        )
        addView(
            LinearLayout(activity).apply {
                orientation = HORIZONTAL
                addView(
                    LinearLayout(activity).apply {
                        orientation = VERTICAL
                        setPadding(0, activity.dp(8), activity.dp(9), 0)
                        addView(label("Live preview", 13f, bold = true))
                        addView(
                            preview,
                            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = activity.dp(5) },
                        )
                        addView(
                            LinearLayout(activity).apply {
                                orientation = HORIZONTAL
                                addView(reduceMotion, LayoutParams(0, activity.dp(40), 1f).apply { marginEnd = activity.dp(3) })
                                addView(highContrast, LayoutParams(0, activity.dp(40), 1f).apply { marginEnd = activity.dp(3) })
                                addView(subtleCorners, LayoutParams(0, activity.dp(40), 1f))
                            },
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(7) },
                        )
                    },
                    LayoutParams(0, LayoutParams.MATCH_PARENT, 0.95f),
                )
                addView(
                    ScrollView(activity).apply {
                        isFocusable = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        addView(
                            LinearLayout(activity).apply {
                                orientation = VERTICAL
                                setPadding(activity.dp(9), activity.dp(8), 0, activity.dp(12))
                                addView(label("Tune the palette", 13f, bold = true))
                                addView(accentHue)
                                addView(accentStrength)
                                addView(accentBrightness)
                                addView(backgroundHue)
                                addView(backgroundStrength)
                                addView(backgroundBrightness)
                                addView(label("Starting palettes", 13f, bold = true).apply {
                                    setPadding(0, activity.dp(9), 0, activity.dp(4))
                                })
                                addView(
                                    GridLayout(activity).apply {
                                        columnCount = 3
                                        presets.forEach { preset ->
                                            val swatch = NativeThemeSwatchView(activity).apply {
                                                tag = "appearance:preset:${preset.name.lowercase()}"
                                                isFocusable = true
                                                isClickable = true
                                                contentDescription = "${preset.name} palette"
                                                setOnClickListener { onColors(ThemeColorPolicy.sanitize(preset.colors)) }
                                                onFocusChangeListener = focusListener(tag as String)
                                            }
                                            presetChoices[preset.name] = swatch
                                            addView(
                                                swatch,
                                                GridLayout.LayoutParams().apply {
                                                    width = 0
                                                    height = activity.dp(46)
                                                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                                                    setMargins(0, 0, activity.dp(5), activity.dp(5))
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    },
                    LayoutParams(0, LayoutParams.MATCH_PARENT, 1.05f),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        update(initialState)
    }

    fun update(next: NativeAppearanceUiState) {
        state = next.copy(colors = ThemeColorPolicy.sanitize(next.colors))
        val colors = state.colors
        val customAccent = colors.accentArgb
        val customBackground = colors.backgroundArgb
        modeChoices.forEach { (mode, view) ->
            val sample = modeSample(mode, colors)
            view.show(modeLabel(mode), sample.backgroundArgb, sample.accentArgb, state.mode == mode)
        }
        presets.forEach { preset ->
            val safe = ThemeColorPolicy.sanitize(preset.colors)
            presetChoices[preset.name]?.show(
                preset.name,
                safe.backgroundArgb,
                safe.accentArgb,
                state.mode == AppearanceMode.CUSTOM && colors == safe,
            )
        }
        preview.show(NativeTheme.palette.focus, NativeTheme.palette.background)
        val accent = ThemeColorPolicy.toHsl(customAccent)
        val background = ThemeColorPolicy.toHsl(customBackground)
        accentHue.show("Accent hue", "${accent.hue}°", hueColors(), accent.hue / 30)
        accentStrength.show("Accent strength", "${accent.saturation}%", saturationColors(accent), accent.saturation / 10)
        accentBrightness.show("Accent brightness", "${accent.lightness}%", lightnessColors(accent), accent.lightness / 10)
        backgroundHue.show("Background hue", "${background.hue}°", hueColors(lightness = 28), background.hue / 30)
        backgroundStrength.show(
            "Background strength",
            "${background.saturation}%",
            saturationColors(background),
            background.saturation / 10,
        )
        backgroundBrightness.show(
            "Background brightness",
            "${background.lightness}%",
            lightnessColors(background),
            background.lightness / 10,
        )
        reduceMotion.text = if (state.reducedMotion) "✓  Reduce motion" else "Reduce motion"
        reduceMotion.isSelected = state.reducedMotion
        highContrast.text = if (state.highContrast) "✓  High contrast" else "High contrast"
        highContrast.isSelected = state.highContrast
        subtleCorners.text = if (state.subtleRoundedCorners) "✓  Soft corners" else "Soft corners"
        subtleCorners.isSelected = state.subtleRoundedCorners
    }

    private fun adjustAccentHue(direction: Int) = onColors(
        state.colors.copy(accentArgb = ThemeColorPolicy.adjustHue(state.colors.accentArgb, direction * 10)),
    )

    private fun adjustAccentStrength(direction: Int) = onColors(
        state.colors.copy(accentArgb = ThemeColorPolicy.adjustSaturation(state.colors.accentArgb, direction * 5)),
    )

    private fun adjustAccentBrightness(direction: Int) = onColors(
        state.colors.copy(accentArgb = ThemeColorPolicy.adjustLightness(state.colors.accentArgb, direction * 5)),
    )

    private fun adjustBackgroundHue(direction: Int) = onColors(
        state.colors.copy(backgroundArgb = ThemeColorPolicy.adjustHue(state.colors.backgroundArgb, direction * 10)),
    )

    private fun adjustBackgroundStrength(direction: Int) = onColors(
        state.colors.copy(backgroundArgb = ThemeColorPolicy.adjustSaturation(state.colors.backgroundArgb, direction * 5)),
    )

    private fun adjustBackgroundBrightness(direction: Int) = onColors(
        state.colors.copy(backgroundArgb = ThemeColorPolicy.adjustLightness(state.colors.backgroundArgb, direction * 3)),
    )

    private fun track(label: String, onAdjust: (Int) -> Unit) = NativeColorTrackView(activity).apply {
        tag = "appearance:track:${label.lowercase().replace(' ', '-')}"
        setAdjustmentListener(onAdjust)
        onFocusChangeListener = focusListener(tag as String)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(43)).apply { topMargin = activity.dp(3) }
    }

    private fun toggle(label: String, onClick: () -> Unit) = TextView(activity).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(NativeTheme.palette.text)
        background = activity.nativeFlatFocusableBackground()
        isFocusable = true
        isClickable = true
        tag = "appearance:toggle:${label.lowercase().replace(' ', '-')}"
        setOnClickListener { onClick() }
        onFocusChangeListener = focusListener(tag as String)
    }

    private fun focusListener(key: String) = OnFocusChangeListener { view, focused ->
        view.invalidate()
        if (focused) onFocused(key, view)
    }

    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(NativeTheme.palette.text)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun modeLabel(mode: AppearanceMode): String = when (mode) {
        AppearanceMode.SYSTEM -> "TV default"
        AppearanceMode.DARK -> "Dark"
        AppearanceMode.LIGHT -> "Light"
        AppearanceMode.CUSTOM -> "Custom"
    }

    private fun modeSample(mode: AppearanceMode, custom: CustomThemeColors): CustomThemeColors = when (mode) {
        AppearanceMode.SYSTEM -> CustomThemeColors(0xFFFF7048.toInt(), 0xFF152235.toInt())
        AppearanceMode.DARK -> CustomThemeColors(0xFFFF7048.toInt(), 0xFF07111F.toInt())
        AppearanceMode.LIGHT -> CustomThemeColors(0xFF1565C0.toInt(), 0xFFF3F6FA.toInt())
        AppearanceMode.CUSTOM -> custom
    }

    private fun hueColors(lightness: Int = 55): IntArray = IntArray(12) { index ->
        ThemeColorPolicy.hslToArgb(HslColor(index * 30, 82, lightness))
    }

    private fun saturationColors(base: HslColor): IntArray = IntArray(11) { index ->
        ThemeColorPolicy.hslToArgb(base.copy(saturation = index * 10))
    }

    private fun lightnessColors(base: HslColor): IntArray = IntArray(11) { index ->
        ThemeColorPolicy.hslToArgb(base.copy(lightness = (index * 9 + 5).coerceAtMost(95)))
    }
}

private class NativeThemeSwatchView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var label = ""
    private var backgroundColor = NativeTheme.palette.background
    private var accentColor = NativeTheme.palette.focus
    private var chosen = false

    fun show(label: String, background: Int, accent: Int, selected: Boolean) {
        this.label = label
        backgroundColor = background
        accentColor = accent
        chosen = selected
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        bounds.set(1f, 1f, width - 1f, height - 1f)
        val radius = if (NativeTheme.subtleRoundedCorners) context.dp(4).toFloat() else 0f
        paint.style = Paint.Style.FILL
        paint.color = backgroundColor
        canvas.drawRoundRect(bounds, radius, radius, paint)
        paint.color = accentColor
        canvas.drawRect(1f, height - context.dp(7).toFloat(), width - 1f, height - 1f, paint)
        paint.color = ThemeColorPolicy.readableForeground(backgroundColor)
        paint.textSize = context.dp(13).toFloat()
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(label, context.dp(9).toFloat(), height / 2f + context.dp(4), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(if (hasFocus()) 3 else if (chosen) 2 else 1).toFloat()
        paint.color = if (hasFocus()) NativeTheme.palette.focus else if (chosen) accentColor else NativeTheme.palette.divider
        canvas.drawRoundRect(bounds, radius, radius, paint)
    }
}

private class NativeColorTrackView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var label = ""
    private var value = ""
    private var colors = intArrayOf(NativeTheme.palette.focus)
    private var selectedIndex = 0
    private var onAdjust: (Int) -> Unit = {}

    init {
        isFocusable = true
        isClickable = true
        contentDescription = "Color adjustment"
        setOnClickListener { onAdjust(1) }
    }

    fun setAdjustmentListener(listener: (Int) -> Unit) {
        onAdjust = listener
    }

    fun show(label: String, value: String, colors: IntArray, selectedIndex: Int) {
        this.label = label
        this.value = value
        this.colors = colors
        this.selectedIndex = selectedIndex.coerceIn(colors.indices)
        contentDescription = "$label, $value, use left and right to change"
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { onAdjust(-1); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { onAdjust(1); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onDraw(canvas: Canvas) {
        val trackLeft = (width * 0.43f).coerceAtLeast(context.dp(150).toFloat())
        val trackRight = (width - context.dp(46)).toFloat()
        val trackTop = context.dp(20).toFloat()
        val segmentWidth = (trackRight - trackLeft) / colors.size
        paint.style = Paint.Style.FILL
        colors.forEachIndexed { index, color ->
            paint.color = color
            canvas.drawRect(
                trackLeft + index * segmentWidth,
                trackTop,
                trackLeft + (index + 1) * segmentWidth + 1,
                trackTop + context.dp(11),
                paint,
            )
        }
        val markerX = trackLeft + (selectedIndex + 0.5f) * segmentWidth
        paint.color = NativeTheme.palette.text
        canvas.drawRect(markerX - 2, trackTop - context.dp(3), markerX + 2, trackTop + context.dp(14), paint)
        paint.textSize = context.dp(12).toFloat()
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = NativeTheme.palette.text
        canvas.drawText(label, context.dp(8).toFloat(), context.dp(27).toFloat(), paint)
        paint.textSize = context.dp(11).toFloat()
        paint.color = NativeTheme.palette.secondaryText
        canvas.drawText(value, trackRight + context.dp(7), context.dp(27).toFloat(), paint)
        if (hasFocus()) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dp(2).toFloat()
            paint.color = NativeTheme.palette.focus
            val radius = if (NativeTheme.subtleRoundedCorners) context.dp(4).toFloat() else 0f
            canvas.drawRoundRect(1f, 1f, width - 1f, height - 1f, radius, radius, paint)
        }
    }
}
