package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import app.opah.tv.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class NativeMotionCamera(
    val name: String,
    val label: String,
)

internal data class NativeMotionSearchUiState(
    val subtitle: String,
    val tabs: List<NativeBrowserTab>,
    val cameras: List<NativeMotionCamera>,
    val selectedCameraName: String?,
    val regionIndex: Int,
    val searching: Boolean,
    val progress: Double?,
    val resultRows: List<NativeRowModel>,
    val emptyMessage: String,
)

@SuppressLint("ViewConstructor")
internal class NativeMotionSearchSurface(
    private val activity: ComponentActivity,
    initialState: NativeMotionSearchUiState,
    private val cachedBitmap: (String) -> Bitmap?,
    private val refreshBitmap: suspend (String, Int) -> Bitmap?,
    private val onActivate: (String) -> Unit,
    private val onCamera: (String) -> Unit,
    private val onRegion: (Int) -> Unit,
    private val onSearch: () -> Unit,
    private val onFocused: (String, View) -> Unit,
    private val onNavigateOutLeft: () -> Unit,
) : LinearLayout(activity) {
    private val subtitle = text(13f, secondary = true)
    private val tabs = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val cameras = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val preview = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(NativeTheme.palette.panel)
    }
    private val grid = GridLayout(activity).apply {
        rowCount = 3
        columnCount = 3
    }
    private val areaHint = text(12f, secondary = true)
    private val searchButton = TextView(activity).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(NativeTheme.palette.text)
        setTypeface(typeface, Typeface.BOLD)
        isFocusable = true
        isClickable = true
        background = activity.nativeFlatFocusableBackground()
        setPadding(activity.dp(13), activity.dp(6), activity.dp(13), activity.dp(6))
        setOnClickListener { onSearch() }
        tag = "activity:motion:start"
        onFocusChangeListener = focusListener(tag as String)
    }
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1_000
        visibility = View.GONE
    }
    private val resultsTitle = text(16f, bold = true).apply {
        setText(R.string.native_motion_results_title)
    }
    private val empty = text(13f, secondary = true).apply {
        gravity = Gravity.CENTER
        setPadding(activity.dp(12), activity.dp(18), activity.dp(12), activity.dp(18))
    }
    private val results = RecyclerView(activity).apply {
        isFocusable = false
        layoutManager = NativeLinearLayoutManager(activity)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(0, 0, activity.dp(4), activity.dp(20))
        addNativeFlatDividers()
    }
    private val resultAdapter = NativeListAdapter(
        onActivate = onActivate,
        onFocused = onFocused,
    )
    private val regionButtons = mutableListOf<TextView>()
    private var previewJob: Job? = null
    private var displayedCamera: String? = null
    private var state = initialState

    init {
        orientation = VERTICAL
        addView(
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    LinearLayout(activity).apply {
                        orientation = VERTICAL
                        addView(text(26f, bold = true).apply { setText(R.string.native_activity_title) })
                        addView(subtitle)
                    },
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(tabs)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = activity.dp(9) },
        )
        addView(
            LinearLayout(activity).apply {
                orientation = HORIZONTAL
                addView(
                    LinearLayout(activity).apply {
                        orientation = VERTICAL
                        setPadding(0, 0, activity.dp(10), 0)
                        addView(text(14f, bold = true).apply { setText(R.string.native_motion_choose_camera) })
                        addView(
                            HorizontalScrollView(activity).apply {
                                isFocusable = false
                                isHorizontalScrollBarEnabled = false
                                overScrollMode = View.OVER_SCROLL_NEVER
                                addView(cameras, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
                            },
                            LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(43)).apply { topMargin = activity.dp(3) },
                        )
                        addView(text(14f, bold = true).apply {
                            setText(R.string.native_motion_choose_area)
                            setPadding(0, activity.dp(8), 0, activity.dp(4))
                        })
                        addView(
                            FrameLayout(activity).apply {
                                setBackgroundColor(NativeTheme.palette.panel)
                                addView(preview, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                                repeat(9) { index ->
                                    val button = TextView(activity).apply {
                                        id = View.generateViewId()
                                        tag = "activity:motion:region:$index"
                                        isFocusable = true
                                        isClickable = true
                                        gravity = Gravity.CENTER
                                        contentDescription = motionRegionName(index)
                                        background = activity.nativeMotionRegionBackground()
                                        setOnClickListener { onRegion(index) }
                                        onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                                            view.invalidate()
                                            if (focused) {
                                                areaHint.text = motionRegionName(index)
                                                onFocused(tag as String, view)
                                            }
                                        }
                                    }
                                    regionButtons += button
                                    grid.addView(
                                        button,
                                        GridLayout.LayoutParams(
                                            GridLayout.spec(index / 3, 1f),
                                            GridLayout.spec(index % 3, 1f),
                                        ).apply {
                                            width = 0
                                            height = 0
                                            setMargins(activity.dp(1), activity.dp(1), activity.dp(1), activity.dp(1))
                                        },
                                    )
                                }
                                addView(grid, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                            },
                            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
                        )
                        addView(
                            LinearLayout(activity).apply {
                                gravity = Gravity.CENTER_VERTICAL
                                addView(areaHint, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                                addView(searchButton, LayoutParams(LayoutParams.WRAP_CONTENT, activity.dp(38)))
                            },
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(6) },
                        )
                        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(3)).apply { topMargin = activity.dp(4) })
                    },
                    LayoutParams(0, LayoutParams.MATCH_PARENT, 1.12f),
                )
                addView(
                    View(activity).apply { setBackgroundColor(NativeTheme.palette.divider) },
                    LayoutParams(activity.dp(1), LayoutParams.MATCH_PARENT).apply { marginEnd = activity.dp(10) },
                )
                addView(
                    LinearLayout(activity).apply {
                        orientation = VERTICAL
                        addView(resultsTitle)
                        addView(
                            FrameLayout(activity).apply {
                                results.adapter = resultAdapter
                                addView(results, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                                addView(empty, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                            },
                            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = activity.dp(5) },
                        )
                    },
                    LayoutParams(0, LayoutParams.MATCH_PARENT, 0.88f),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        update(initialState)
        post {
            val selectedTabIndex = initialState.tabs.indexOfFirst { it.selected }.coerceAtLeast(0)
            tabs.getChildAt(selectedTabIndex)?.requestFocus()
        }
    }

    fun update(next: NativeMotionSearchUiState) {
        state = next
        subtitle.text = next.subtitle
        updateTabs(next.tabs)
        updateCameras(next.cameras, next.selectedCameraName)
        regionButtons.forEachIndexed { index, button ->
            button.isSelected = index == next.regionIndex
            button.isEnabled = !next.searching
            button.alpha = if (button.isEnabled) 1f else 0.65f
            button.text = if (index == next.regionIndex) "✓" else ""
            button.setTextColor(NativeTheme.palette.text)
        }
        areaHint.text = activity.getString(R.string.native_motion_selected_area, motionRegionName(next.regionIndex))
        searchButton.setText(if (next.searching) R.string.native_motion_cancel_search else R.string.native_motion_find)
        progress.visibility = if (next.searching) View.VISIBLE else View.GONE
        progress.progress = ((next.progress ?: 0.0) * progress.max).toInt().coerceIn(0, progress.max)
        resultAdapter.submitList(next.resultRows)
        empty.text = next.emptyMessage
        empty.visibility = if (next.resultRows.isEmpty()) View.VISIBLE else View.GONE
        resultsTitle.text = if (next.resultRows.isEmpty()) {
            activity.getString(R.string.native_motion_results_title)
        } else {
            activity.resources.getQuantityString(
                R.plurals.native_motion_results_count,
                next.resultRows.size,
                next.resultRows.size,
            )
        }
        loadPreview(next.selectedCameraName)
    }

    fun close() {
        previewJob?.cancel()
        previewJob = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val focused = findFocus() ?: return super.dispatchKeyEvent(event)
        when {
            tabs.isAncestorOf(focused) -> when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> return true
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusSelectedCameraOrRegion()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> {
                    val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    if (focusHorizontalSibling(tabs, focused, forward)) return true
                    if (!forward) {
                        onNavigateOutLeft()
                        return true
                    }
                    return true
                }
            }
            cameras.isAncestorOf(focused) -> when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    focusSelectedTab()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusSelectedRegion()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> {
                    val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    if (focusHorizontalSibling(cameras, focused, forward)) return true
                    if (!forward) {
                        onNavigateOutLeft()
                        return true
                    }
                    return true
                }
            }
            grid.isAncestorOf(focused) -> {
                val index = regionButtons.indexOf(focused)
                if (index >= 0) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (index >= 3) regionButtons[index - 3].requestFocus() else focusSelectedCameraOrTab()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (index <= 5) regionButtons[index + 3].requestFocus() else searchButton.requestFocus()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (index % 3 != 0) {
                                regionButtons[index - 1].requestFocus()
                            } else {
                                onNavigateOutLeft()
                            }
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (index % 3 != 2) regionButtons[index + 1].requestFocus() else focusFirstResult()
                            return true
                        }
                    }
                }
            }
            focused === searchButton -> when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_LEFT,
                -> {
                    focusSelectedRegion()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> {
                    focusFirstResult()
                    return true
                }
            }
            results.isAncestorOf(focused) -> when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    focusSelectedRegion()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                -> {
                    val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    if (!results.focusAdjacentRow(
                        resultAdapter,
                        focused,
                        forward = forward,
                    ) && !forward) {
                        searchButton.requestFocus()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
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
                        setPadding(activity.dp(10), activity.dp(5), activity.dp(10), activity.dp(5))
                        setOnClickListener { onActivate(tab.key) }
                        onFocusChangeListener = focusListener(tab.key)
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

    private fun updateCameras(models: List<NativeMotionCamera>, selected: String?) {
        val focusedKey = cameras.findFocus()?.tag as? String
        val keys = models.map { "activity:motion:camera:${it.name}" }
        val existingKeys = (0 until cameras.childCount).map { cameras.getChildAt(it).tag as? String }
        if (existingKeys != keys) {
            cameras.removeAllViews()
            models.forEach { camera ->
                cameras.addView(
                    TextView(activity).apply {
                        id = View.generateViewId()
                        tag = "activity:motion:camera:${camera.name}"
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setTextColor(NativeTheme.palette.text)
                        background = activity.nativeFlatFocusableBackground()
                        setPadding(activity.dp(11), activity.dp(5), activity.dp(11), activity.dp(5))
                        setOnClickListener { onCamera(camera.name) }
                        onFocusChangeListener = focusListener(tag as String)
                    },
                    LayoutParams(LayoutParams.WRAP_CONTENT, activity.dp(36)).apply { marginEnd = activity.dp(3) },
                )
            }
        }
        models.forEachIndexed { index, camera ->
            (cameras.getChildAt(index) as? TextView)?.apply {
                text = camera.label
                isFocusable = !state.searching
                isClickable = !state.searching
                isEnabled = !state.searching
                isSelected = camera.name == selected
                alpha = if (isEnabled) 1f else 0.5f
            }
        }
        if (focusedKey != null) cameras.post { cameras.findViewWithTag<View>(focusedKey)?.requestFocus() }
    }

    private fun loadPreview(cameraName: String?) {
        if (cameraName == displayedCamera && preview.drawable != null) return
        displayedCamera = cameraName
        previewJob?.cancel()
        preview.setImageDrawable(null)
        if (cameraName == null) return
        cachedBitmap(cameraName)?.let(preview::setImageBitmap)
        previewJob = activity.lifecycleScope.launch {
            refreshBitmap(cameraName, 720)?.let { bitmap ->
                if (displayedCamera == cameraName) preview.setImageBitmap(bitmap)
            }
        }
    }

    private fun focusListener(key: String) = OnFocusChangeListener { view, focused ->
        if (focused) onFocused(key, view)
    }

    private fun focusSelectedTab(): Boolean {
        val index = state.tabs.indexOfFirst { it.selected && it.enabled }
            .takeIf { it >= 0 }
            ?: state.tabs.indexOfFirst(NativeBrowserTab::enabled)
        return tabs.getChildAt(index)?.requestFocus() == true
    }

    private fun focusSelectedCameraOrTab(): Boolean = focusSelectedCamera() || focusSelectedTab()

    private fun focusSelectedCameraOrRegion(): Boolean = focusSelectedCamera() || focusSelectedRegion()

    private fun focusSelectedCamera(): Boolean {
        val index = state.cameras.indexOfFirst { it.name == state.selectedCameraName }
            .takeIf { it >= 0 }
            ?: state.cameras.indices.firstOrNull()
            ?: return false
        return cameras.getChildAt(index)?.takeIf { it.isFocusable && it.isEnabled }?.requestFocus() == true
    }

    private fun focusSelectedRegion(): Boolean = regionButtons
        .getOrNull(state.regionIndex.coerceIn(0, regionButtons.lastIndex))
        ?.takeIf { it.isFocusable && it.isEnabled }
        ?.requestFocus() == true

    private fun focusFirstResult(): Boolean {
        val position = resultAdapter.currentList.indexOfFirst(NativeRowModel::focusable)
        return position >= 0 && results.focusRow(position)
    }

    private fun focusHorizontalSibling(group: ViewGroup, focused: View, forward: Boolean): Boolean {
        val index = group.indexOfChild(focused)
        if (index < 0) return false
        val range = if (forward) index + 1 until group.childCount else index - 1 downTo 0
        val target = range.map(group::getChildAt).firstOrNull { it.isFocusable && it.isEnabled }
        return target?.requestFocus() == true
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

private fun motionRegionName(index: Int): String = when (index.coerceIn(0, 8)) {
    0 -> "Top left"
    1 -> "Top center"
    2 -> "Top right"
    3 -> "Middle left"
    4 -> "Center"
    5 -> "Middle right"
    6 -> "Bottom left"
    7 -> "Bottom center"
    else -> "Bottom right"
}
