package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import app.opah.tv.R

internal data class NativeBrowserTab(
    val key: String,
    val label: String,
    val selected: Boolean,
    val enabled: Boolean = true,
)

/** Page-specific controls shown below the primary page tabs. */
internal data class NativeBrowserTool(
    val key: String,
    val label: String,
    val value: String = "",
    val enabled: Boolean = true,
    val field: Boolean = false,
    val primary: Boolean = false,
)

internal data class NativeBrowserDetail(
    val key: String,
    val title: String,
    val subtitle: String,
    val body: String = "",
    val thumbnail: NativeThumbnailRequest? = null,
    val actions: List<NativeRowModel> = emptyList(),
)

internal data class NativeBrowserUiState(
    val title: String,
    val subtitle: String,
    val tabs: List<NativeBrowserTab>,
    val tools: List<NativeBrowserTool> = emptyList(),
    val rows: List<NativeRowModel>,
    val details: Map<String, NativeBrowserDetail>,
    val emptyTitle: String,
    val emptyBody: String,
)

@SuppressLint("ViewConstructor")
internal class NativeMediaBrowserSurface(
    private val activity: ComponentActivity,
    initialState: NativeBrowserUiState,
    private val onActivate: (String) -> Unit,
    private val onFocused: (String, View) -> Unit,
    private val onThumbnailRequested: (NativeRowModel, ImageView) -> Unit,
    private val onNavigateOutLeft: () -> Unit,
) : LinearLayout(activity) {
    private val subtitle = TextView(activity).apply {
        textSize = 13f
        setTextColor(NativeTheme.palette.secondaryText)
    }
    private val tabs = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val tools = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }
    private val list = RecyclerView(activity).apply {
        isFocusable = false
        layoutManager = NativeLinearLayoutManager(activity)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(0, 0, activity.dp(8), activity.dp(38))
        addNativeFlatDividers()
    }
    private val detailImage = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(NativeTheme.palette.panel)
    }
    private val detailTitle = text(21f, bold = true)
    private val detailSubtitle = text(13f, secondary = true)
    private val detailBody = text(13f, secondary = true).apply { maxLines = 4 }
    private val detailActions = RecyclerView(activity).apply {
        isFocusable = false
        layoutManager = NativeLinearLayoutManager(activity)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        addNativeFlatDividers()
    }
    private val listAdapter = NativeListAdapter(
        onActivate = onActivate,
        onFocused = { key, view -> select(key); onFocused(key, view) },
        onThumbnailRequested = onThumbnailRequested,
    )
    private val actionAdapter = NativeListAdapter(
        onActivate = onActivate,
        onFocused = onFocused,
        onThumbnailRequested = onThumbnailRequested,
    )
    private var currentState = initialState
    private var selectedKey: String? = null
    private var firstUpdate = true

    init {
        orientation = VERTICAL
        addView(
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    LinearLayout(activity).apply {
                        orientation = VERTICAL
                        addView(text(26f, bold = true).apply { text = initialState.title })
                        addView(subtitle)
                    },
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(tabs)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = activity.dp(12) },
        )
        addView(
            tools,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = activity.dp(9) },
        )
        list.adapter = listAdapter
        detailActions.adapter = actionAdapter
        addView(
            LinearLayout(activity).apply {
                orientation = HORIZONTAL
                addView(
                    FrameLayout(activity).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setPadding(0, activity.dp(2), activity.dp(4), 0)
                        addView(list, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                    },
                    LayoutParams(0, LayoutParams.MATCH_PARENT, 1.08f).apply { marginEnd = activity.dp(7) },
                )
                addView(
                    View(activity).apply { setBackgroundColor(NativeTheme.palette.divider) },
                    LayoutParams(activity.dp(1), LayoutParams.MATCH_PARENT).apply { marginEnd = activity.dp(10) },
                )
                addView(
                    LinearLayout(activity).apply {
                        orientation = VERTICAL
                        setPadding(0, activity.dp(2), 0, activity.dp(8))
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        addView(detailImage, LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(190)))
                        addView(detailTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(9) })
                        addView(detailSubtitle)
                        addView(detailBody, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(5) })
                        addView(detailActions, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = activity.dp(8) })
                    },
                    LayoutParams(0, LayoutParams.MATCH_PARENT, 0.92f),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        update(initialState)
    }

    fun update(state: NativeBrowserUiState) {
        currentState = state
        subtitle.text = state.subtitle
        updateTabs(state.tabs)
        updateTools(state.tools)
        val preferred = selectedKey?.takeIf(state.details::containsKey) ?: state.rows.firstOrNull()?.key
        listAdapter.submitList(state.rows) {
            if (preferred != null) select(preferred) else showEmpty(state.emptyTitle, state.emptyBody)
            if (firstUpdate) {
                firstUpdate = false
                list.post {
                    if (state.rows.isNotEmpty()) list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    else {
                        val firstTool = (0 until tools.childCount)
                            .map(tools::getChildAt)
                            .firstOrNull { it.isFocusable && it.isEnabled }
                        if (firstTool != null) firstTool.requestFocus()
                        else {
                            val selectedTabIndex = state.tabs.indexOfFirst { it.selected }.coerceAtLeast(0)
                            tabs.getChildAt(selectedTabIndex)?.requestFocus()
                        }
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = findFocus()
            when {
                focused != null && list.isAncestorOf(focused) -> when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        focusDetailActions()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    -> {
                        val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        if (list.focusAdjacentRow(listAdapter, focused, forward)) return true
                        if (!forward) focusLastToolOrSelectedTab()
                        return true
                    }
                }
                focused != null && detailActions.isAncestorOf(focused) -> when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        restoreSelectedListFocus()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    -> {
                        detailActions.focusAdjacentRow(
                            actionAdapter,
                            focused,
                            forward = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN,
                        )
                        return true
                    }
                }
                focused != null && tabs.isAncestorOf(focused) && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusFirstToolOrList()
                    return true
                }
                focused != null && tabs.isAncestorOf(focused) && (
                    event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                        event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    ) -> {
                    val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    if (!focusHorizontalSibling(tabs, focused, forward) && !forward) onNavigateOutLeft()
                    return true
                }
                focused != null && tools.isAncestorOf(focused) -> when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        focusSelectedTab()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusFirstListRow()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    -> {
                        val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        if (!focusHorizontalSibling(tools, focused, forward) && !forward) onNavigateOutLeft()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun focusDetailActions(): Boolean {
        val target = firstFocusableChild(detailActions) ?: return false
        target.requestFocus()
        return true
    }

    private fun updateTabs(models: List<NativeBrowserTab>) {
        val focusedKey = tabs.findFocus()?.tag as? String
        val existingKeys = (0 until tabs.childCount).map { tabs.getChildAt(it).tag as? String }
        if (existingKeys != models.map(NativeBrowserTab::key)) {
            tabs.removeAllViews()
            models.forEach { tab ->
                tabs.addView(
                    TextView(activity).apply {
                        id = View.generateViewId()
                        tag = tab.key
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setTextColor(NativeTheme.palette.text)
                        background = activity.nativeFlatFocusableBackground()
                        setPadding(activity.dp(11), activity.dp(5), activity.dp(11), activity.dp(5))
                        setOnClickListener { onActivate(tab.key) }
                    },
                    LayoutParams(LayoutParams.WRAP_CONTENT, activity.dp(36)).apply { marginStart = activity.dp(3) },
                )
            }
        }
        models.forEachIndexed { index, tab ->
            (tabs.getChildAt(index) as? TextView)?.apply {
                text = tab.label
                isFocusable = tab.enabled
                isClickable = tab.enabled
                isEnabled = tab.enabled
                isSelected = tab.selected
                alpha = if (tab.enabled) 1f else 0.5f
            }
        }
        if (focusedKey != null) tabs.post { tabs.findViewWithTag<View>(focusedKey)?.requestFocus() }
    }

    private fun updateTools(models: List<NativeBrowserTool>) {
        val focusedKey = tools.findFocus()?.tag as? String
        val existingKeys = (0 until tools.childCount).map { tools.getChildAt(it).tag as? String }
        if (existingKeys != models.map(NativeBrowserTool::key)) {
            tools.removeAllViews()
            models.forEach { tool ->
                tools.addView(
                    TextView(activity).apply {
                        id = View.generateViewId()
                        tag = tool.key
                        textSize = if (tool.field) 15f else 13f
                        gravity = if (tool.field) Gravity.CENTER_VERTICAL else Gravity.CENTER
                        setTextColor(NativeTheme.palette.text)
                        if (tool.primary) setTypeface(typeface, Typeface.BOLD)
                        background = if (tool.field) {
                            activity.nativeFlatFieldBackground()
                        } else {
                            activity.nativeFlatFocusableBackground()
                        }
                        setPadding(activity.dp(if (tool.field) 13 else 10), activity.dp(5), activity.dp(if (tool.field) 13 else 10), activity.dp(5))
                        setOnClickListener { onActivate(tool.key) }
                    },
                    LayoutParams(
                        if (tool.field) 0 else LayoutParams.WRAP_CONTENT,
                        activity.dp(if (tool.field) 42 else 36),
                        if (tool.field) 1f else 0f,
                    ).apply {
                        marginEnd = activity.dp(if (tool.field) 8 else 3)
                    },
                )
            }
        }
        models.forEachIndexed { index, tool ->
            (tools.getChildAt(index) as? TextView)?.apply {
                text = buildString {
                    append(tool.label)
                    if (tool.value.isNotBlank()) append("   ${tool.value}")
                }
                isFocusable = tool.enabled
                isClickable = tool.enabled
                isEnabled = tool.enabled
                alpha = if (tool.enabled) 1f else 0.5f
            }
        }
        tools.visibility = if (models.isEmpty()) View.GONE else View.VISIBLE
        if (focusedKey != null) tools.post { tools.findViewWithTag<View>(focusedKey)?.requestFocus() }
    }

    private fun select(key: String) {
        selectedKey = key
        val detail = currentState.details[key] ?: return
        val focusedActionKey = detailActions.findFocus()?.tag as? String
        detailTitle.text = detail.title
        detailSubtitle.text = detail.subtitle
        detailBody.text = detail.body
        detailBody.visibility = if (detail.body.isBlank()) View.GONE else View.VISIBLE
        actionAdapter.submitList(detail.actions) {
            if (focusedActionKey != null) {
                val position = actionAdapter.currentList.indexOfFirst { it.key == focusedActionKey }
                if (position >= 0) {
                    detailActions.scrollToPosition(position)
                    detailActions.post {
                        detailActions.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                    }
                }
            }
        }
        detailImage.setImageDrawable(null)
        detailImage.visibility = if (detail.thumbnail == null) View.GONE else View.VISIBLE
        detail.thumbnail?.let { request ->
            detailImage.tag = request.cacheKey
            onThumbnailRequested(
                NativeRowModel("browser-detail:${detail.key}", detail.title, thumbnail = request),
                detailImage,
            )
        }
    }

    private fun showEmpty(title: String, body: String) {
        selectedKey = null
        detailImage.visibility = View.GONE
        detailTitle.text = title
        detailSubtitle.text = body
        detailBody.visibility = View.GONE
        actionAdapter.submitList(emptyList())
    }

    private fun restoreSelectedListFocus() {
        val position = selectedKey?.let { key -> listAdapter.currentList.indexOfFirst { it.key == key } }
            ?.takeIf { it >= 0 }
            ?: return
        list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() ?: run {
            list.scrollToPosition(position)
            list.post { list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
        }
    }

    private fun focusFirstListRow(): Boolean {
        val position = listAdapter.currentList.indexOfFirst(NativeRowModel::focusable)
        return if (position >= 0) list.focusRow(position) else false
    }

    private fun focusSelectedTab(): Boolean {
        val selectedIndex = currentState.tabs.indexOfFirst { it.selected && it.enabled }
            .takeIf { it >= 0 }
            ?: currentState.tabs.indexOfFirst(NativeBrowserTab::enabled)
        return tabs.getChildAt(selectedIndex)?.requestFocus() == true
    }

    private fun focusFirstToolOrList(): Boolean {
        val firstTool = (0 until tools.childCount)
            .map(tools::getChildAt)
            .firstOrNull { it.isFocusable && it.isEnabled }
        return firstTool?.requestFocus() == true || focusFirstListRow()
    }

    private fun focusLastToolOrSelectedTab(): Boolean {
        val lastTool = (tools.childCount - 1 downTo 0)
            .map(tools::getChildAt)
            .firstOrNull { it.isFocusable && it.isEnabled }
        return lastTool?.requestFocus() == true || focusSelectedTab()
    }

    private fun focusHorizontalSibling(group: ViewGroup, focused: View, forward: Boolean): Boolean {
        val index = group.indexOfChild(focused)
        if (index < 0) return false
        val positions = if (forward) (index + 1 until group.childCount) else (index - 1 downTo 0)
        val target = positions
            .map(group::getChildAt)
            .firstOrNull { it.isFocusable && it.isEnabled }
        return target?.requestFocus() == true
    }

    private fun firstFocusableChild(group: ViewGroup): View? {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child.isFocusable && child.isEnabled) return child
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

    private fun text(size: Float, bold: Boolean = false, secondary: Boolean = false) = TextView(activity).apply {
        textSize = size
        setTextColor(if (secondary) NativeTheme.palette.secondaryText else NativeTheme.palette.text)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
