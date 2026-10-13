package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.opah.tv.R

/** Setup, connection progress, and recovery share presentation, never authentication state. */
@SuppressLint("ViewConstructor", "SetTextI18n")
internal class NativeWelcomeSurface(
    private val activity: ComponentActivity,
    title: String,
    message: String,
    actions: List<NativeRowModel>,
    private val onActivate: (String) -> Unit,
    private val onFocused: (String, View) -> Unit = { _, _ -> },
    loading: Boolean = false,
) : FrameLayout(activity) {
    private val heading = label(26f, bold = true)
    private val explanation = label(16f, secondary = true).apply {
        setLineSpacing(activity.dp(2).toFloat(), 1.08f)
    }
    private val progress = ProgressBar(activity).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(NativeTheme.palette.focus)
        contentDescription = "Getting things ready"
        isFocusable = false
    }
    private val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val scroller = ScrollView(activity).apply {
        isFocusable = true
        isFillViewport = false
        overScrollMode = OVER_SCROLL_NEVER
        background = activity.nativeGlassPanelBackground(strong = true)
        clipToOutline = true
    }
    private val rowViews = linkedMapOf<String, WelcomeRow>()
    private var models = emptyList<NativeRowModel>()

    init {
        setPadding(activity.dp(36), activity.dp(24), activity.dp(36), activity.dp(24))
        background = activity.nativeCanvasBackground()
        scroller.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(16), activity.dp(20), activity.dp(16))
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(activity).apply {
                    setImageResource(R.drawable.opah_brand_mark)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(activity.dp(60), activity.dp(48)).apply { marginEnd = activity.dp(12) })
                addView(heading, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(progress, LinearLayout.LayoutParams(activity.dp(28), activity.dp(28)).apply { marginStart = activity.dp(12) })
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(explanation, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = activity.dp(10)
                bottomMargin = activity.dp(12)
            })
            addView(rows, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroller, LayoutParams(activity.dp(680), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        update(title, message, actions, loading)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        scroller.layoutParams.width = activity.dp(680).coerceAtMost(available)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun update(title: String, message: String, actions: List<NativeRowModel>, loading: Boolean = false) {
        val focusedKey = findFocus()?.tag as? String
        val previousIndex = models.indexOfFirst { it.key == focusedKey }.coerceAtLeast(0)
        val ownedFocus = hasFocus()
        heading.text = title
        explanation.text = message
        explanation.visibility = if (message.isBlank()) GONE else VISIBLE
        progress.visibility = if (loading && !NativeTheme.reducedMotion) VISIBLE else GONE
        val keys = actions.map(NativeRowModel::key)
        rowViews.keys.filter { it !in keys }.toList().forEach { key ->
            rowViews.remove(key)?.let { rows.removeView(it.container) }
        }
        actions.forEachIndexed { index, model ->
            val row = rowViews.getOrPut(model.key) { createRow(model.key) }
            if (rows.indexOfChild(row.container) != index) {
                rows.removeView(row.container)
                rows.addView(row.container, index, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = activity.dp(if (index == 0) 0 else 6)
                })
            }
            row.title.text = model.title
            row.title.visibility = if (model.title.isBlank()) GONE else VISIBLE
            row.description.text = model.description
            row.description.visibility = if (model.description.isBlank()) GONE else VISIBLE
            row.value.text = model.value
            row.value.visibility = if (model.value.isBlank()) GONE else VISIBLE
            row.container.isEnabled = model.enabled
            row.container.isFocusable = model.focusable
            row.container.isClickable = model.actionable
            row.container.isSelected = model.selected
            row.container.alpha = if (model.enabled) 1f else 0.5f
            row.container.background = if (model.actionable) activity.nativeFlatFocusableBackground() else null
            row.container.contentDescription = listOf(model.title, model.description, model.value).filter(String::isNotBlank).joinToString(", ")
        }
        models = actions
        if (ownedFocus && (focusedKey == null || actions.none { it.key == focusedKey && it.focusable })) {
            val fallback = actions.indices.filter { actions[it].focusable }.minByOrNull { kotlin.math.abs(it - previousIndex) }
            if (fallback != null) rowViews[actions[fallback].key]?.container?.requestFocus() else scroller.requestFocus()
        }
    }

    fun focusPrimaryAction(): Boolean = models.firstOrNull(NativeRowModel::focusable)?.key
        ?.let { rowViews[it]?.container?.requestFocus() } ?: scroller.requestFocus()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            val key = findFocus()?.tag as? String
            val position = models.indexOfFirst { it.key == key }
            val target = adjacentFocusableRowPosition(models, position, forward)
            if (target != null) rowViews[models[target].key]?.container?.requestFocus()
            else if (scroller.hasFocus()) scroller.scrollBy(0, activity.dp(if (forward) 72 else -72))
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun createRow(key: String): WelcomeRow {
        val title = label(17f, bold = true)
        val description = label(14f, secondary = true).apply { setLineSpacing(activity.dp(2).toFloat(), 1f) }
        val value = label(14f, secondary = true)
        val container = LinearLayout(activity).apply {
            id = View.generateViewId()
            tag = key
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
            addView(title)
            addView(description)
            addView(value)
            setOnClickListener { onActivate(key) }
            onFocusChangeListener = OnFocusChangeListener { view, focused ->
                if (focused) onFocused(key, view)
            }
        }
        return WelcomeRow(container, title, description, value)
    }

    private fun label(size: Float, bold: Boolean = false, secondary: Boolean = false) = TextView(activity).apply {
        textSize = size
        setTextColor(if (secondary) NativeTheme.palette.secondaryText else NativeTheme.palette.text)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

    private data class WelcomeRow(val container: LinearLayout, val title: TextView, val description: TextView, val value: TextView)
}
