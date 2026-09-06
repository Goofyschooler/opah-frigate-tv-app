package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import app.opah.tv.R

internal data class NativeSettingsUiState(
    val title: String,
    val subtitle: String,
    val categories: List<NativeRowModel>,
    val detailRows: List<NativeRowModel>,
    val selectedCategoryKey: String?,
    val detailTabs: List<NativeBrowserTab> = emptyList(),
    val preferredDetailFocusKey: String? = null,
    val detailContent: View? = null,
)

@SuppressLint("ViewConstructor", "SetTextI18n")
internal class NativeSettingsSurface(
    private val activity: ComponentActivity,
    initialState: NativeSettingsUiState,
    private val onActivate: (String) -> Unit,
    private val onFocused: (String, View) -> Unit,
    private val onAdjust: (String, Int) -> Unit,
) : LinearLayout(activity) {
    private val title = TextView(activity).apply {
        textSize = 26f
        setTextColor(NativeTheme.palette.text)
        setTypeface(typeface, Typeface.BOLD)
    }
    private val subtitle = TextView(activity).apply {
        textSize = 13f
        setTextColor(NativeTheme.palette.secondaryText)
        maxLines = 2
    }
    private val categoryList = NativeSettingsColumn(activity).apply {
        isFocusable = false
        layoutManager = NativeLinearLayoutManager(activity)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(0, 0, activity.dp(8), activity.dp(20))
        addNativeFlatDividers()
    }
    private val detailList = NativeSettingsColumn(activity).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        layoutManager = NativeLinearLayoutManager(activity)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(activity.dp(10), 0, activity.dp(2), activity.dp(28))
        addNativeFlatDividers()
    }
    private val detailScrollGuide = TextView(activity).apply {
        textSize = 12f
        setTextColor(NativeTheme.palette.focus)
        visibility = View.INVISIBLE
        setPadding(activity.dp(12), activity.dp(2), 0, activity.dp(4))
    }
    private val detailTabs = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(activity.dp(10), 0, activity.dp(2), activity.dp(6))
    }
    private val standardDetailColumn = LinearLayout(activity).apply {
        orientation = VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(0, activity.dp(2), 0, 0)
        addView(detailTabs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(detailScrollGuide, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(detailList, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }
    private val detailHost = FrameLayout(activity)
    private var currentDetailContent: View? = null
    private var preferDetailFocus = false
    private var rememberedDetailKey: String? = null
    private var detailHasActions = false
    private var selectedCategoryKey: String? = initialState.selectedCategoryKey
    private val categoryAdapter = NativeListAdapter(
        onActivate = onActivate,
        onFocused = onFocused,
    )
    private val detailAdapter = NativeListAdapter(
        onActivate = onActivate,
        onAdjust = onAdjust,
        onFocused = { key, view ->
            preferDetailFocus = true
            rememberedDetailKey = key
            onFocused(key, view)
        },
    )
    private var firstUpdate = true

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        addView(
            LinearLayout(activity).apply {
                orientation = VERTICAL
                addView(title)
                addView(subtitle)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = activity.dp(8) },
        )
        categoryList.adapter = categoryAdapter
        detailList.adapter = detailAdapter
        addView(
            LinearLayout(activity).apply {
                orientation = HORIZONTAL
                addView(
                    LinearLayout(activity).apply {
                        orientation = VERTICAL
                        setBackgroundColor(Color.TRANSPARENT)
                        setPadding(0, activity.dp(2), 0, 0)
                        addView(categoryList, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
                    },
                    LayoutParams(activity.dp(255), LayoutParams.MATCH_PARENT),
                )
                addView(
                    View(activity).apply { setBackgroundColor(NativeTheme.palette.divider) },
                    LayoutParams(activity.dp(1), LayoutParams.MATCH_PARENT).apply {
                        marginStart = activity.dp(7)
                        marginEnd = activity.dp(7)
                    },
                )
                addView(detailHost, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        update(initialState)
    }

    fun update(state: NativeSettingsUiState) {
        val focusedDetailKey =
            detailList.findFocus()?.tag as? String
                ?: rememberedDetailKey.takeIf { preferDetailFocus }
                ?: state.preferredDetailFocusKey?.takeIf { key ->
                    state.detailRows.any { it.key == key && it.focusable }
                }
        if (focusedDetailKey != null) {
            preferDetailFocus = true
            rememberedDetailKey = focusedDetailKey
        }
        title.text = state.title
        subtitle.text = state.subtitle
        selectedCategoryKey = state.selectedCategoryKey
        detailHasActions = state.detailRows.any(NativeRowModel::focusable)
        detailScrollGuide.text = if (detailHasActions) {
            "Use ↑ ↓ to read • press Select for actions"
        } else {
            "Use ↑ ↓ to read this page"
        }
        updateDetailTabs(state.detailTabs)
        updateDetailContent(state.detailContent)
        categoryAdapter.submitList(state.categories)
        if (state.detailContent != null) {
            detailAdapter.submitList(emptyList())
            if (firstUpdate) {
                firstUpdate = false
                val preferredDetail = state.preferredDetailFocusKey?.let { key ->
                    state.detailContent.findViewWithTag<View>(key)
                }
                if (preferredDetail != null) {
                    preferredDetail.post { preferredDetail.requestFocus() }
                } else {
                    categoryList.post {
                        val index = state.categories.indexOfFirst { it.key == state.selectedCategoryKey }.coerceAtLeast(0)
                        categoryList.scrollToPosition(index)
                        categoryList.post { categoryList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
                    }
                }
            }
            return
        }
        detailAdapter.submitList(state.detailRows) {
            if (focusedDetailKey != null) {
                val position = detailAdapter.currentList.indexOfFirst { it.key == focusedDetailKey }
                if (position >= 0) {
                    detailList.scrollToPosition(position)
                    detailList.post {
                        detailList.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                    }
                }
            } else if (firstUpdate) {
                firstUpdate = false
                categoryList.post {
                    val index = state.categories.indexOfFirst { it.key == state.selectedCategoryKey }.coerceAtLeast(0)
                    categoryList.scrollToPosition(index)
                    categoryList.post { categoryList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
                }
            }
            firstUpdate = false
        }
    }

    fun focusSelectedCategory(key: String?) {
        preferDetailFocus = false
        val requestedKey = key ?: selectedCategoryKey
        val index = categoryAdapter.currentList.indexOfFirst { it.key == requestedKey }.coerceAtLeast(0)
        categoryList.scrollToPosition(index)
        categoryList.post { categoryList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = findFocus()
            when {
                focused != null && categoryList.isAncestorOf(focused) -> {
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        preferDetailFocus = true
                        val customTarget = currentDetailContent?.let(::firstFocusableDescendant)
                        if (customTarget != null) customTarget.requestFocus() else detailList.requestFocus()
                        return true
                    }
                    preferDetailFocus = false
                }
                focused === detailList -> when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!detailList.canScrollVertically(-1)) {
                            detailList.focusUpTarget?.takeIf(View::isFocusable)?.requestFocus()
                            return true
                        }
                        detailList.scrollBy(0, -(detailList.height * DETAIL_SCROLL_FRACTION).toInt())
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        detailList.scrollBy(0, (detailList.height * DETAIL_SCROLL_FRACTION).toInt())
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    -> {
                        if (focusFirstDetailAction()) return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        focusSelectedCategory(null)
                        return true
                    }
                }
                focused != null && detailList.isAncestorOf(focused) -> {
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        preferDetailFocus = false
                    } else {
                        preferDetailFocus = true
                        rememberedDetailKey = focused.tag as? String ?: rememberedDetailKey
                    }
                }
                focused != null && detailTabs.isAncestorOf(focused) -> preferDetailFocus = true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun focusFirstDetailAction(): Boolean {
        val position = detailAdapter.currentList.indexOfFirst(NativeRowModel::focusable)
        if (position < 0) return false
        detailList.scrollToPosition(position)
        detailList.post {
            detailList.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
        return true
    }

    private fun updateDetailTabs(models: List<NativeBrowserTab>) {
        val focusedKey = detailTabs.findFocus()?.tag as? String
        detailTabs.removeAllViews()
        models.forEach { tab ->
            detailTabs.addView(
                TextView(activity).apply {
                    tag = tab.key
                    text = tab.label
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(NativeTheme.palette.text)
                    isFocusable = tab.enabled
                    isClickable = tab.enabled
                    isEnabled = tab.enabled
                    isSelected = tab.selected
                    alpha = if (tab.enabled) 1f else 0.5f
                    background = activity.nativeFlatFocusableBackground()
                    setPadding(activity.dp(11), activity.dp(5), activity.dp(11), activity.dp(5))
                    setOnClickListener { onActivate(tab.key) }
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, activity.dp(36)).apply { marginEnd = activity.dp(3) },
            )
        }
        detailTabs.visibility = if (models.isEmpty()) View.GONE else View.VISIBLE
        detailList.focusUpTarget = models.firstOrNull { it.selected }?.key?.let { key ->
            detailTabs.findViewWithTag(key)
        } ?: detailTabs.getChildAt(0)
        if (focusedKey != null) detailTabs.post { detailTabs.findViewWithTag<View>(focusedKey)?.requestFocus() }
    }

    private fun updateDetailContent(content: View?) {
        if (currentDetailContent === content && detailHost.isNotEmpty()) return
        currentDetailContent = content
        detailHost.removeAllViews()
        val child = content ?: standardDetailColumn
        (child.parent as? ViewGroup)?.removeView(child)
        detailHost.addView(
            child,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    private fun firstFocusableDescendant(view: View): View? {
        if (view.isFocusable && view.isEnabled && view.isVisible) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            firstFocusableDescendant(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun ViewGroup.isAncestorOf(view: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }

    init {
        detailList.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            detailScrollGuide.visibility = if (focused) View.VISIBLE else View.INVISIBLE
        }
    }

    private companion object {
        const val DETAIL_SCROLL_FRACTION = 0.72f
    }
}

/**
 * Keeps vertical navigation inside its current settings column. Moving between
 * category and detail columns is always an explicit left/right action, which
 * prevents a list boundary from unexpectedly sending focus across the page.
 */
private class NativeSettingsColumn(context: android.content.Context) : RecyclerView(context) {
    var focusUpTarget: View? = null

    override fun focusSearch(focused: View?, direction: Int): View? {
        val proposed = super.focusSearch(focused, direction)
        val vertical = direction == View.FOCUS_UP || direction == View.FOCUS_DOWN
        if (
            vertical &&
            focused != null &&
            focused !== this &&
            isAncestorOf(focused) &&
            (proposed == null || !isAncestorOf(proposed)) &&
            isFocusable
        ) {
            return this
        }
        if (
            direction == View.FOCUS_UP &&
            focused != null && isAncestorOf(focused) &&
            (proposed == null || !isAncestorOf(proposed)) &&
            focusUpTarget?.isFocusable == true
        ) {
            return focusUpTarget
        }
        return if (
            vertical &&
            focused != null && isAncestorOf(focused) &&
            (proposed == null || !isAncestorOf(proposed))
        ) {
            focused
        } else {
            proposed
        }
    }

    private fun ViewGroup.isAncestorOf(view: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }
}
