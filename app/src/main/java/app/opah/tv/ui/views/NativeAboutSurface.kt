package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.opah.tv.R

/** A reading page: project information is continuous text, not a sequence of focusable rows. */
@SuppressLint("ViewConstructor", "SetTextI18n")
internal class NativeAboutSurface(
    private val activity: ComponentActivity,
    aboutText: String,
    versionLabel: String,
    private val onOpenProject: () -> Unit,
    private val onNavigateOutLeft: () -> Unit = {},
) : LinearLayout(activity) {
    private val version = label(14f, secondary = true)
    private val readingText = label(16f).apply {
        setLineSpacing(activity.dp(2).toFloat(), 1f)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private val reading = ScrollView(activity).apply {
        id = View.generateViewId()
        tag = "about:reading"
        isFocusable = true
        isFillViewport = false
        overScrollMode = OVER_SCROLL_NEVER
        background = activity.nativeFlatScrollRegionBackground()
        setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
        addView(readingText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        contentDescription = "About Opah, use up and down to read"
    }
    private val project = label(15f, bold = true).apply {
        id = View.generateViewId()
        tag = "about:open-project"
        text = "Open project"
        contentDescription = "Open project, source code, documentation, and support"
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        background = activity.nativeFlatFocusableBackground()
        setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_right, 0)
        compoundDrawablesRelative.forEach { it?.setTint(NativeTheme.palette.text) }
        setOnClickListener { onOpenProject() }
    }

    init {
        orientation = VERTICAL
        setPadding(activity.dp(8), activity.dp(6), activity.dp(8), activity.dp(6))
        addView(
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(activity).apply {
                    setImageResource(R.drawable.opah_brand_mark)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(activity.dp(60), activity.dp(48)).apply { marginEnd = activity.dp(12) })
                addView(LinearLayout(activity).apply {
                    orientation = VERTICAL
                    addView(label(22f, bold = true).apply { text = "Opah" })
                    addView(version)
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = activity.dp(6) },
        )
        addView(reading, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(project, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(6) })
        update(aboutText, versionLabel)
    }

    fun update(aboutText: String, versionLabel: String) {
        if (readingText.text.toString() != aboutText) readingText.text = aboutText
        version.text = versionLabel
    }

    fun focusReading(): Boolean = reading.requestFocus()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { onNavigateOutLeft(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (project.hasFocus()) reading.requestFocus()
                    else reading.scrollBy(0, -activity.dp(80))
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (reading.hasFocus() && reading.canScrollVertically(1)) reading.scrollBy(0, activity.dp(80))
                    else project.requestFocus()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun label(size: Float, bold: Boolean = false, secondary: Boolean = false) = TextView(activity).apply {
        textSize = size
        setTextColor(if (secondary) NativeTheme.palette.secondaryText else NativeTheme.palette.text)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }
}
