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
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
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
    val errorMessage: String? = null,
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
    val loading: Boolean = false,
    val preferredSelectionKey: String? = null,
)

internal enum class NativeBrowserPane { TABS, TOOLS, LIST, DETAILS }

internal data class NativeBrowserScrollAnchor(
    val key: String? = null,
    val position: Int = 0,
    val offset: Int = 0,
)

/** Presentation identities only; the controller owns the matching route and filters. */
internal data class NativeMediaBrowserBookmark(
    val selectedKey: String? = null,
    val selectedPosition: Int = 0,
    val focusedKey: String? = null,
    val pane: NativeBrowserPane = NativeBrowserPane.LIST,
    val listAnchor: NativeBrowserScrollAnchor = NativeBrowserScrollAnchor(),
    val detailAnchor: NativeBrowserScrollAnchor = NativeBrowserScrollAnchor(),
)

@SuppressLint("ViewConstructor")
internal class NativeMediaBrowserSurface(
    private val activity: ComponentActivity,
    initialState: NativeBrowserUiState,
    private val onActivate: (String) -> Unit,
    private val onFocused: (String, View) -> Unit,
    private val onThumbnailRequested: (NativeRowModel, ImageView) -> Unit,
    private val onNavigateOutLeft: () -> Unit,
    initialBookmark: NativeMediaBrowserBookmark? = null,
) : LinearLayout(activity) {
    private val pageTitle = text(26f, bold = true)
    private val subtitle = text(13f, secondary = true)
    private val tabs = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = activity.nativeGlassPanelBackground()
        setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
        clipChildren = false
    }
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
        setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(12))
        clipToPadding = false
    }
    private val detailImage = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = activity.nativeGlassPanelBackground(strong = true)
        clipToOutline = true
    }
    private val detailTitle = text(17f, bold = true)
    private val detailSubtitle = text(12f, secondary = true)
    private val detailBody = text(12f, secondary = true).apply { maxLines = 4 }
    private val detailError = text(13f).apply {
        tag = "browser:detail-error"
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    private val detailActions = RecyclerView(activity).apply {
        isFocusable = false
        layoutManager = NativeLinearLayoutManager(activity)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
    }
    private val listAdapter = NativeListAdapter(
        onActivate = onActivate,
        onFocused = { key, view ->
            if (currentState.preferredSelectionKey == key) handledPreferredSelectionKey = key
            select(key)
            onFocused(key, view)
        },
        onThumbnailRequested = onThumbnailRequested,
        compact = true,
        showCurrentItemMarker = true,
    )
    private val actionAdapter = NativeListAdapter(
        onActivate = onActivate,
        onFocused = onFocused,
        onThumbnailRequested = onThumbnailRequested,
        compact = true,
    )
    private var currentState = initialState
    private var selectedKey: String? = null
    private var firstUpdate = true
    private var pendingBookmark = initialBookmark
    private var pendingFocusMoved = false
    private var updateGeneration = 0
    private var handledPreferredSelectionKey: String? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        addView(
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    LinearLayout(activity).apply {
                        orientation = VERTICAL
                        addView(pageTitle)
                        addView(subtitle)
                    },
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(tabs)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = activity.dp(9) },
        )
        addView(
            tools,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = activity.dp(7) },
        )
        list.adapter = listAdapter
        detailActions.adapter = actionAdapter
        addView(
            LinearLayout(activity).apply {
                orientation = HORIZONTAL
                addView(
                    FrameLayout(activity).apply {
                        background = activity.nativeGlassPanelBackground(strong = true)
                        setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
                        addView(list, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                    },
                    LayoutParams(0, LayoutParams.MATCH_PARENT, 1.12f).apply { marginEnd = activity.dp(12) },
                )
                addView(
                    LinearLayout(activity).apply {
                        orientation = VERTICAL
                        setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(10))
                        background = activity.nativeGlassPanelBackground(strong = true)
                        addView(detailImage, LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(128)))
                        addView(detailTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                        addView(detailSubtitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(3) })
                        addView(detailBody, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(4) })
                        addView(detailError, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(6) })
                        addView(detailActions, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = activity.dp(6) })
                    },
                    LayoutParams(0, LayoutParams.MATCH_PARENT, 0.88f),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        update(initialState)
    }

    fun update(state: NativeBrowserUiState) {
        val generation = ++updateGeneration
        val bookmark = pendingBookmark ?: if (firstUpdate) null else captureBookmark()
        val restoreFocus = pendingBookmark != null || firstUpdate || hasFocus()
        val explicitRestore = (pendingBookmark != null && !pendingFocusMoved) || firstUpdate
        val awaitingRows = state.loading && state.rows.isEmpty()
        // A loading placeholder is not an authoritative empty result. Keep only
        // presentation identities until rows arrive; never keep old visible data.
        pendingBookmark = bookmark.takeIf { awaitingRows }
        if (!awaitingRows) pendingFocusMoved = false
        currentState = state
        pageTitle.text = state.title
        subtitle.text = state.subtitle
        updateTabs(state.tabs)
        updateTools(state.tools)
        val availableKeys = state.rows.mapTo(hashSetOf(), NativeRowModel::key)
        if (state.preferredSelectionKey == null) handledPreferredSelectionKey = null
        val requestedSelection = state.preferredSelectionKey?.takeIf {
            it != handledPreferredSelectionKey && it in availableKeys && it in state.details
        }
        val preferred = requestedSelection ?: bookmark?.selectedKey?.takeIf { it in availableKeys && it in state.details }
            ?: selectedKey?.takeIf { it in availableKeys && it in state.details }
            ?: state.rows.getOrNull((bookmark?.selectedPosition ?: 0).coerceIn(0, (state.rows.size - 1).coerceAtLeast(0)))?.key?.takeIf { it in state.details }
            ?: state.rows.firstOrNull { it.key in state.details }?.key
        if (preferred == null) showEmpty(state.emptyTitle, state.emptyBody)
        listAdapter.submitList(state.rows) {
            if (generation != updateGeneration) return@submitList
            firstUpdate = false
            val restore: () -> Unit = {
                if (generation == updateGeneration) {
                    if (requestedSelection != null) {
                        list.post {
                            if (generation == updateGeneration) focusRowKey(list, listAdapter, requestedSelection, 0)
                        }
                    } else if (awaitingRows) {
                        if (restoreFocus && !hasFocus()) post { if (generation == updateGeneration) focusDefault() }
                    } else if (bookmark != null && state.rows.isNotEmpty()) {
                        restoreBookmarkViews(bookmark, restoreFocus, explicitRestore, generation)
                    } else if (restoreFocus) post { if (generation == updateGeneration) focusDefault() }
                }
            }
            if (preferred != null) select(preferred, restore)
            else restore()
        }
    }

    fun captureBookmark(): NativeMediaBrowserBookmark {
        if (currentState.loading && currentState.rows.isEmpty()) pendingBookmark?.let { return it }
        val focused = findFocus()
        return NativeMediaBrowserBookmark(
            selectedKey = selectedKey,
            selectedPosition = listAdapter.currentList.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0),
            focusedKey = focused?.tag as? String,
            pane = when {
                focused != null && tabs.isAncestorOf(focused) -> NativeBrowserPane.TABS
                focused != null && tools.isAncestorOf(focused) -> NativeBrowserPane.TOOLS
                focused != null && detailActions.isAncestorOf(focused) -> NativeBrowserPane.DETAILS
                else -> NativeBrowserPane.LIST
            },
            listAnchor = captureAnchor(list, listAdapter),
            detailAnchor = captureAnchor(detailActions, actionAdapter),
        )
    }

    fun restoreBookmark(bookmark: NativeMediaBrowserBookmark) {
        pendingBookmark = bookmark
        pendingFocusMoved = false
        update(currentState)
    }

    private fun restoreBookmarkViews(
        bookmark: NativeMediaBrowserBookmark,
        requestFocus: Boolean,
        explicit: Boolean,
        generation: Int,
    ) {
        restoreAnchor(list, listAdapter, bookmark.listAnchor)
        restoreAnchor(detailActions, actionAdapter, bookmark.detailAnchor)
        // Restoring focus before RecyclerView applies the anchor would replace its exact offset.
        doOnPreDraw {
            if (generation != updateGeneration || !requestFocus) return@doOnPreDraw
            val focused = activity.currentFocus
            if (!explicit && focused != null && focused !== this && !isAncestorOf(focused)) return@doOnPreDraw
            when (bookmark.pane) {
                NativeBrowserPane.TABS -> if (!focusTagged(tabs, bookmark.focusedKey)) focusSelectedTab()
                NativeBrowserPane.TOOLS -> if (!focusTagged(tools, bookmark.focusedKey)) focusFirstToolOrList()
                NativeBrowserPane.DETAILS -> {
                    if (!focusRowKey(detailActions, actionAdapter, bookmark.focusedKey, bookmark.detailAnchor.position)) {
                        restoreSelectedListFocus()
                    }
                }
                NativeBrowserPane.LIST -> {
                    if (!focusRowKey(list, listAdapter, bookmark.focusedKey, bookmark.selectedPosition)) focusDefault()
                }
            }
        }
    }

    private fun captureAnchor(view: RecyclerView, adapter: NativeListAdapter): NativeBrowserScrollAnchor {
        val manager = view.layoutManager as? LinearLayoutManager ?: return NativeBrowserScrollAnchor()
        val position = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        return NativeBrowserScrollAnchor(
            adapter.currentList.getOrNull(position)?.key,
            position,
            manager.findViewByPosition(position)?.let { manager.getDecoratedTop(it) - view.paddingTop } ?: 0,
        )
    }

    private fun restoreAnchor(view: RecyclerView, adapter: NativeListAdapter, anchor: NativeBrowserScrollAnchor) {
        if (adapter.currentList.isEmpty()) return
        val position = adapter.currentList.indexOfFirst { it.key == anchor.key }.takeIf { it >= 0 }
            ?: anchor.position.coerceIn(0, adapter.currentList.lastIndex)
        (view.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, anchor.offset)
    }

    private fun focusRowKey(view: RecyclerView, adapter: NativeListAdapter, key: String?, fallback: Int): Boolean {
        val rows = adapter.currentList
        val exact = rows.indexOfFirst { it.key == key && it.focusable }
        val position = exact.takeIf { it >= 0 } ?: rows.indices
            .filter { rows[it].focusable }.minByOrNull { kotlin.math.abs(it - fallback) } ?: return false
        return view.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() == true || view.focusRow(position)
    }

    private fun focusTagged(group: ViewGroup, key: String?): Boolean = key?.let {
        group.findViewWithTag<View>(it)?.takeIf { target -> target.isFocusable && target.isEnabled }?.requestFocus()
    } == true

    private fun focusDefault(): Boolean = focusFirstListRow() || focusFirstToolOrList() || focusSelectedTab()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val trackIntent = event.action == KeyEvent.ACTION_DOWN && when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> true
            else -> false
        }
        if (trackIntent) rememberLoadingFocusIntent()
        val handled = dispatchContentKeyEvent(event)
        if (trackIntent) rememberLoadingFocusIntent()
        return handled
    }

    private fun rememberLoadingFocusIntent() {
        if (!currentState.loading || currentState.rows.isNotEmpty()) return
        val saved = pendingBookmark ?: return
        pendingFocusMoved = true
        val focused = findFocus() ?: return
        pendingBookmark = saved.copy(
            focusedKey = focused.tag as? String,
            pane = when {
                tabs.isAncestorOf(focused) -> NativeBrowserPane.TABS
                tools.isAncestorOf(focused) -> NativeBrowserPane.TOOLS
                detailActions.isAncestorOf(focused) -> NativeBrowserPane.DETAILS
                else -> NativeBrowserPane.LIST
            },
        )
    }

    private fun dispatchContentKeyEvent(event: KeyEvent): Boolean {
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
                        includeFontPadding = false
                        gravity = Gravity.CENTER
                        setTextColor(NativeTheme.palette.text)
                        background = activity.nativeFlatFocusableBackground()
                        setPadding(activity.dp(11), activity.dp(5), activity.dp(11), activity.dp(5))
                        setOnClickListener { onActivate(tab.key) }
                        onFocusChangeListener = OnFocusChangeListener { view, focused ->
                            view.applyNativeFocusMotion(focused)
                            if (focused) onFocused(tab.key, view)
                        }
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
                        includeFontPadding = false
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
                        onFocusChangeListener = OnFocusChangeListener { view, focused ->
                            if (!tool.field) view.applyNativeFocusMotion(focused)
                            if (focused) onFocused(tool.key, view)
                        }
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

    private fun select(key: String, onReady: () -> Unit = {}) {
        val detail = currentState.details[key]?.takeIf { currentState.rows.any { it.key == key } } ?: run {
            // Boundary actions such as Load more are focusable but have no media detail.
            onReady()
            return
        }
        selectedKey = key
        listAdapter.setCurrentItem(key)
        detailTitle.text = detail.title
        detailSubtitle.text = detail.subtitle
        detailSubtitle.visibility = if (detail.subtitle.isBlank()) View.GONE else View.VISIBLE
        detailBody.text = detail.body
        detailBody.visibility = if (detail.body.isBlank()) View.GONE else View.VISIBLE
        val error = detail.errorMessage.orEmpty()
        if (detailError.text.toString() != error) detailError.text = error
        detailError.isVisible = error.isNotBlank()
        actionAdapter.submitList(detail.actions) {
            onReady()
        }
        detailImage.visibility = if (detail.thumbnail == null) View.GONE else View.VISIBLE
        updateDetailTitleSpacing()
        if (detail.thumbnail == null) {
            detailImage.tag = null
            detailImage.setImageDrawable(null)
        }
        detail.thumbnail?.takeIf { it.cacheKey != detailImage.tag }?.let { request ->
            detailImage.setImageDrawable(null)
            detailImage.tag = request.cacheKey
            onThumbnailRequested(
                NativeRowModel("browser-detail:${detail.key}", detail.title, thumbnail = request),
                detailImage,
            )
        }
    }

    private fun showEmpty(title: String, body: String) {
        selectedKey = null
        listAdapter.setCurrentItem(null)
        detailImage.visibility = View.GONE
        detailImage.tag = null
        detailImage.setImageDrawable(null)
        detailTitle.text = title
        detailSubtitle.text = body
        detailSubtitle.visibility = if (body.isBlank()) View.GONE else View.VISIBLE
        detailBody.visibility = View.GONE
        detailError.text = ""
        detailError.visibility = View.GONE
        updateDetailTitleSpacing()
        actionAdapter.submitList(emptyList())
    }

    private fun updateDetailTitleSpacing() {
        val params = detailTitle.layoutParams as LayoutParams
        val topMargin = if (detailImage.isVisible) activity.dp(7) else 0
        if (params.topMargin != topMargin) {
            params.topMargin = topMargin
            detailTitle.layoutParams = params
        }
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
        includeFontPadding = false
        setTextColor(if (secondary) NativeTheme.palette.secondaryText else NativeTheme.palette.text)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
