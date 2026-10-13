package app.opah.tv.ui.views

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.opah.tv.R

internal fun RecyclerView.addNativeFlatDividers(startInsetDp: Int = 12) {
    addItemDecoration(NativeFlatDividerDecoration(context.dp(startInsetDp)))
}

internal fun RecyclerView.focusRow(position: Int): Boolean {
    if (position < 0 || position >= (adapter?.itemCount ?: 0)) return false
    val visible = findViewHolderForAdapterPosition(position)?.itemView
    if (visible != null) return visible.requestFocus()
    scrollToPosition(position)
    val itemId = adapter?.takeIf { it.hasStableIds() }?.getItemId(position)
    post {
        if (position < (adapter?.itemCount ?: 0) && (itemId == null || adapter?.getItemId(position) == itemId)) {
            findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
    }
    return true
}

internal fun RecyclerView.focusAdjacentRow(
    rowAdapter: NativeListAdapter,
    focused: View,
    forward: Boolean,
): Boolean {
    val currentPosition = findContainingViewHolder(focused)?.bindingAdapterPosition
        ?.takeIf { it != RecyclerView.NO_POSITION }
        ?: return false
    val positions = if (forward) (currentPosition + 1)..rowAdapter.currentList.lastIndex else (currentPosition - 1) downTo 0
    val target = positions.firstOrNull { rowAdapter.isFocusableRow(rowAdapter.currentList[it]) } ?: return false
    return focusRow(target)
}

private class NativeFlatDividerDecoration(
    private val startInset: Int,
) : RecyclerView.ItemDecoration() {
    private val paint = Paint().apply {
        color = NativeTheme.palette.divider
        strokeWidth = 1f
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        paint.color = NativeTheme.palette.divider
        val left = parent.paddingLeft + startInset.toFloat()
        val right = (parent.width - parent.paddingRight).toFloat()
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val bottom = child.bottom + child.translationY
            canvas.drawLine(left, bottom, right, bottom, paint)
        }
    }
}

internal class NativeListAdapter(
    private val onActivate: (String) -> Unit,
    private val onFocused: (String, View) -> Unit,
    private val onAdjust: (String, Int) -> Unit = { _, _ -> },
    private val onThumbnailRequested: (NativeRowModel, ImageView) -> Unit = { _, _ -> },
    private val compact: Boolean = false,
    private val focusInformationRows: Boolean = false,
    private val showCurrentItemMarker: Boolean = false,
) : ListAdapter<NativeRowModel, RecyclerView.ViewHolder>(DiffCallback) {
    private val viewIds = mutableMapOf<String, Int>()
    private var recyclerView: RecyclerView? = null
    private var currentItemKey: String? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = stableNativeItemId(getItem(position).key)

    override fun getItemViewType(position: Int): Int = getItem(position).kind.ordinal

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun setCurrentItem(key: String?) {
        if (currentItemKey == key) return
        currentItemKey = key
        // Focus may change during RecyclerView layout, when adapter notifications
        // are forbidden. Only drawable/visibility states change; row geometry stays fixed.
        val list = recyclerView ?: return
        for (index in 0 until list.childCount) {
            val holder = list.getChildViewHolder(list.getChildAt(index)) as? RowHolder ?: continue
            val item = currentList.getOrNull(holder.bindingAdapterPosition) ?: continue
            holder.showSelection(item, showCurrentItemMarker, item.key == currentItemKey)
        }
    }

    fun isFocusableRow(item: NativeRowModel): Boolean = item.focusable ||
        (focusInformationRows && item.enabled && item.kind in READING_ROW_KINDS)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            NativeRowKind.SECTION.ordinal -> SectionHolder(
                LayoutInflater.from(parent.context).inflate(
                    R.layout.native_flat_section_header,
                    parent,
                    false,
                ),
            )
            NativeRowKind.INFORMATION.ordinal -> InformationHolder(
                LayoutInflater.from(parent.context).inflate(
                    R.layout.native_flat_information_row,
                    parent,
                    false,
                ),
            )
            NativeRowKind.VISUAL.ordinal -> VisualHolder(
                LayoutInflater.from(parent.context).inflate(
                    R.layout.native_visual_meter_row,
                    parent,
                    false,
                ),
            )
            NativeRowKind.DASHBOARD.ordinal -> DashboardHolder(
                LayoutInflater.from(parent.context).inflate(
                    R.layout.native_dashboard_row,
                    parent,
                    false,
                ),
            )
            NativeRowKind.THEME_PREVIEW.ordinal -> ThemePreviewHolder(
                LayoutInflater.from(parent.context).inflate(
                    R.layout.native_theme_preview_row,
                    parent,
                    false,
                ),
            )
            else -> RowHolder(
                LayoutInflater.from(parent.context).inflate(
                    R.layout.native_flat_list_row,
                    parent,
                    false,
                ),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is SectionHolder -> holder.bind(item)
            is InformationHolder -> holder.bind(item)
            is VisualHolder -> holder.bind(item)
            is DashboardHolder -> holder.bind(item)
            is ThemePreviewHolder -> holder.bind(item)
            is RowHolder -> holder.bind(
                item = item,
                viewId = viewId(item.key),
                secondaryViewId = item.secondaryActionKey?.let(::viewId),
                onActivate = onActivate,
                onFocused = onFocused,
                onAdjust = onAdjust,
                onThumbnailRequested = onThumbnailRequested,
                compact = compact,
            )
        }
        if (holder is RowHolder) holder.showSelection(item, showCurrentItemMarker, item.key == currentItemKey)
        if (item.kind in READING_ROW_KINDS) {
            holder.itemView.apply {
                id = viewId(item.key)
                tag = item.key
                isFocusable = isFocusableRow(item)
                isClickable = false
                if (focusInformationRows) background = context.nativeFlatScrollRegionBackground()
                onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                    if (focused) onFocused(item.key, view)
                }
            }
        }
    }

    private class InformationHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.native_information_title)
        private val description: TextView = view.findViewById(R.id.native_information_description)
        private val value: TextView = view.findViewById(R.id.native_information_value)

        fun bind(item: NativeRowModel) {
            val palette = NativeTheme.palette
            itemView.background = null
            itemView.isClickable = false
            itemView.isEnabled = item.enabled
            itemView.alpha = if (item.enabled) 1f else 0.48f
            itemView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            title.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            description.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            value.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            title.setTextColor(palette.text)
            description.setTextColor(palette.secondaryText)
            value.setTextColor(palette.secondaryText)
            title.text = item.title
            title.showColorIndicator(item.indicatorColor)
            title.visibility = if (item.title.isBlank()) View.GONE else View.VISIBLE
            description.text = item.description
            description.visibility = if (item.description.isBlank()) View.GONE else View.VISIBLE
            value.text = item.value
            value.visibility = if (item.value.isBlank()) View.GONE else View.VISIBLE
            itemView.contentDescription = buildList {
                if (item.title.isNotBlank()) add(item.title)
                if (item.description.isNotBlank()) add(item.description)
                if (item.value.isNotBlank()) add(item.value)
            }.joinToString(", ")
        }
    }

    private class VisualHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.native_visual_title)
        private val description: TextView = view.findViewById(R.id.native_visual_description)
        private val value: TextView = view.findViewById(R.id.native_visual_value)
        private val meter: NativeMeterView = view.findViewById(R.id.native_visual_meter)

        fun bind(item: NativeRowModel) {
            itemView.background = null
            itemView.isClickable = false
            title.setTextColor(NativeTheme.palette.text)
            description.setTextColor(NativeTheme.palette.secondaryText)
            value.setTextColor(NativeTheme.palette.secondaryText)
            title.text = item.title
            description.text = item.description
            description.visibility = if (item.description.isBlank()) View.GONE else View.VISIBLE
            value.text = item.value
            value.visibility = if (item.value.isBlank()) View.GONE else View.VISIBLE
            meter.show(item.meterSegments)
            itemView.contentDescription = listOf(item.title, item.description, item.value)
                .filter(String::isNotBlank)
                .joinToString(", ")
        }
    }

    private class DashboardHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val metrics: LinearLayout = view.findViewById(R.id.native_dashboard_metrics)

        fun bind(item: NativeRowModel) {
            itemView.background = itemView.context.nativeGlassPanelBackground(strong = true)
            metrics.removeAllViews()
            item.dashboardMetrics.forEachIndexed { index, metric ->
                metrics.addView(
                    LinearLayout(itemView.context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(itemView.context.dp(8), itemView.context.dp(2), itemView.context.dp(8), itemView.context.dp(2))
                        addView(
                            TextView(itemView.context).apply {
                                text = metric.value
                                textSize = 25f
                                setTextColor(NativeTheme.palette.text)
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                                maxLines = 2
                            },
                        )
                        addView(
                            TextView(itemView.context).apply {
                                text = metric.label
                                textSize = 13f
                                setTextColor(NativeTheme.palette.secondaryText)
                                showColorIndicator(metric.color)
                            },
                        )
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            itemView.contentDescription = item.dashboardMetrics.joinToString(", ") { "${it.label} ${it.value}" }
        }
    }

    private class ThemePreviewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val preview: NativeThemePreviewView = view.findViewById(R.id.native_theme_preview)

        fun bind(item: NativeRowModel) {
            preview.show(
                item.previewAccent ?: NativeTheme.palette.focus,
                item.previewBackground ?: NativeTheme.palette.background,
            )
            itemView.contentDescription = "Live theme preview"
            itemView.isFocusable = false
        }
    }

    fun viewIdForKey(key: String): Int? = viewIds[key]

    fun refreshTheme() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, "theme")
    }

    private fun viewId(key: String): Int = viewIds.getOrPut(key, View::generateViewId)

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumbnail: ImageView = view.findViewById(R.id.native_row_thumbnail)
        private val title: TextView = view.findViewById(R.id.native_row_title)
        private val description: TextView = view.findViewById(R.id.native_row_description)
        private val value: TextView = view.findViewById(R.id.native_row_value)
        private val affordance: TextView = view.findViewById(R.id.native_row_affordance)
        private val selection: View = view.findViewById(R.id.native_row_selection)

        fun showSelection(item: NativeRowModel, showMarker: Boolean, current: Boolean) {
            itemView.isSelected = item.selected || (showMarker && current)
            selection.visibility = if (!showMarker) View.GONE else if (current) View.VISIBLE else View.INVISIBLE
            selection.background = GradientDrawable().apply {
                cornerRadius = itemView.context.dp(2).toFloat()
                val palette = NativeTheme.palette
                setColor(if (ColorUtils.calculateContrast(palette.focus, palette.panelSelected) >= 3.0) palette.focus else palette.text)
            }
        }

        fun bind(
            item: NativeRowModel,
            viewId: Int,
            secondaryViewId: Int?,
            onActivate: (String) -> Unit,
            onFocused: (String, View) -> Unit,
            onAdjust: (String, Int) -> Unit,
            onThumbnailRequested: (NativeRowModel, ImageView) -> Unit,
            compact: Boolean,
        ) {
            val palette = NativeTheme.palette
            itemView.minimumHeight = itemView.context.dp(if (compact) 44 else 48)
            itemView.setPadding(
                itemView.context.dp(10), itemView.context.dp(if (compact) 6 else 7),
                itemView.context.dp(10), itemView.context.dp(if (compact) 6 else 7),
            )
            itemView.background = itemView.context.nativeFlatFocusableBackground()
            thumbnail.clipToOutline = true
            thumbnail.background = itemView.context.nativeFlatThumbnailBackground()
            val thumbnailWidth = itemView.context.dp(if (compact) 96 else 112)
            val thumbnailHeight = itemView.context.dp(if (compact) 54 else 63)
            if (thumbnail.layoutParams.width != thumbnailWidth || thumbnail.layoutParams.height != thumbnailHeight) {
                thumbnail.layoutParams = thumbnail.layoutParams.apply {
                    width = thumbnailWidth
                    height = thumbnailHeight
                }
            }
            title.setTextColor(palette.text)
            title.textSize = if (compact) 16f else 17f
            title.maxLines = 2
            description.setTextColor(palette.secondaryText)
            description.textSize = if (compact) 12f else 13f
            description.maxLines = if (compact) 1 else 2
            value.setTextColor(palette.secondaryText)
            affordance.setTextColor(palette.text)
            listOf(title, description, value, thumbnail).forEach {
                it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            itemView.id = viewId
            itemView.tag = item.key
            itemView.isEnabled = item.enabled
            itemView.isFocusable = item.focusable
            itemView.isClickable = item.actionable
            itemView.isSelected = item.selected
            itemView.alpha = if (item.enabled) 1f else 0.48f
            itemView.nextFocusUpId = View.NO_ID
            itemView.nextFocusDownId = View.NO_ID
            itemView.nextFocusRightId = View.NO_ID
            title.text = item.title
            title.showColorIndicator(item.indicatorColor)
            description.text = item.description
            description.visibility = if (item.description.isBlank()) View.GONE else View.VISIBLE
            val visibleValue = item.value.takeUnless { item.kind == NativeRowKind.TOGGLE }.orEmpty()
            value.text = visibleValue
            value.visibility = if (visibleValue.isBlank()) View.GONE else View.VISIBLE
            affordance.text = item.secondaryActionLabel.ifBlank { when (item.kind) {
                NativeRowKind.ACTION -> "›"
                NativeRowKind.TOGGLE -> if (item.selected) "On" else "Off"
                NativeRowKind.CHOICE -> if (item.selected) "✓" else "›"
                NativeRowKind.ADJUSTMENT -> "‹  ›"
                NativeRowKind.READING -> "›"
                    NativeRowKind.INFORMATION,
                    NativeRowKind.VISUAL,
                    NativeRowKind.DASHBOARD,
                    NativeRowKind.THEME_PREVIEW,
                    NativeRowKind.SECTION,
                    -> ""
            } }
            affordance.visibility = if (affordance.text.isBlank()) View.GONE else View.VISIBLE
            val secondaryKey = item.secondaryActionKey
            if (secondaryKey != null && item.secondaryActionLabel.isNotBlank()) {
                affordance.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                affordance.id = requireNotNull(secondaryViewId)
                affordance.tag = secondaryKey
                affordance.isFocusable = item.enabled
                affordance.isClickable = item.enabled
                affordance.isEnabled = item.enabled
                affordance.background = itemView.context.nativeFlatFocusableBackground()
                affordance.setPadding(
                    itemView.context.dp(10),
                    itemView.context.dp(5),
                    itemView.context.dp(10),
                    itemView.context.dp(5),
                )
                itemView.nextFocusRightId = affordance.id
                affordance.nextFocusLeftId = itemView.id
                affordance.contentDescription = "${item.secondaryActionLabel} ${item.title}"
                affordance.setOnClickListener { onActivate(secondaryKey) }
                affordance.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                    if (focused) onFocused(secondaryKey, view)
                }
            } else {
                affordance.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                affordance.tag = null
                affordance.isFocusable = false
                affordance.isClickable = false
                affordance.background = null
                affordance.setPadding(0, 0, 0, 0)
                affordance.setOnClickListener(null)
                affordance.onFocusChangeListener = null
                if (item.kind == NativeRowKind.TOGGLE) {
                    affordance.background = GradientDrawable().apply {
                        cornerRadius = itemView.context.dp(14).toFloat()
                        setColor(if (item.selected) palette.panelSelected else palette.panel)
                        setStroke(itemView.context.dp(1), if (item.selected) palette.focus else palette.divider)
                    }
                    affordance.setPadding(itemView.context.dp(10), itemView.context.dp(4), itemView.context.dp(10), itemView.context.dp(4))
                }
            }
            val requestedThumbnail = item.thumbnail?.cacheKey
            if (thumbnail.tag != requestedThumbnail) thumbnail.setImageDrawable(null)
            thumbnail.tag = requestedThumbnail
            thumbnail.visibility = if (item.thumbnail == null) View.GONE else View.VISIBLE
            if (item.thumbnail != null) onThumbnailRequested(item, thumbnail)
            itemView.contentDescription = buildList {
                add(item.title)
                if (item.description.isNotBlank()) add(item.description)
                if (item.value.isNotBlank()) add(item.value)
                when (item.kind) {
                    NativeRowKind.ACTION -> add("Action")
                    NativeRowKind.TOGGLE -> add(if (item.selected) "On" else "Off")
                    NativeRowKind.CHOICE -> add(if (item.selected) "Selected" else "Not selected")
                    NativeRowKind.ADJUSTMENT -> add("Use left and right to change")
                    NativeRowKind.READING -> add("Read details")
                    NativeRowKind.INFORMATION,
                    NativeRowKind.VISUAL,
                    NativeRowKind.DASHBOARD,
                    NativeRowKind.THEME_PREVIEW,
                    NativeRowKind.SECTION,
                    -> Unit
                }
            }.joinToString(", ")
            itemView.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = when (item.kind) {
                        NativeRowKind.TOGGLE -> "android.widget.Switch"
                        NativeRowKind.CHOICE -> "android.widget.RadioButton"
                        NativeRowKind.ADJUSTMENT -> "android.widget.SeekBar"
                        NativeRowKind.ACTION,
                        NativeRowKind.READING,
                        -> "android.widget.Button"
                        NativeRowKind.INFORMATION,
                        NativeRowKind.VISUAL,
                        NativeRowKind.DASHBOARD,
                        NativeRowKind.THEME_PREVIEW,
                        NativeRowKind.SECTION,
                        -> "android.widget.TextView"
                    }
                    if (item.kind == NativeRowKind.TOGGLE || item.kind == NativeRowKind.CHOICE) {
                        info.isCheckable = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                            info.setChecked(
                                if (item.selected) {
                                    AccessibilityNodeInfo.CHECKED_STATE_TRUE
                                } else {
                                    AccessibilityNodeInfo.CHECKED_STATE_FALSE
                                },
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            info.isChecked = item.selected
                        }
                    }
                    if (item.kind == NativeRowKind.ADJUSTMENT) {
                        item.adjustmentRange?.let { range ->
                            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                                range.minimum, range.maximum, range.current,
                            )
                        }
                        if (item.enabled) {
                            if (item.adjustmentRange?.canAdjust(1) != false) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                            if (item.adjustmentRange?.canAdjust(-1) != false) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                        }
                    }
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    val direction = when (action) {
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1
                        else -> return super.performAccessibilityAction(host, action, args)
                    }
                    if (item.kind != NativeRowKind.ADJUSTMENT || !item.enabled || item.adjustmentRange?.canAdjust(direction) == false) return false
                    onAdjust(item.key, direction)
                    return true
                }
            }
            itemView.setOnClickListener(if (item.actionable) View.OnClickListener { onActivate(item.key) } else null)
            itemView.setOnKeyListener(
                if (item.kind == NativeRowKind.ADJUSTMENT) {
                    View.OnKeyListener { _, keyCode, event ->
                        if (event.action != android.view.KeyEvent.ACTION_DOWN) return@OnKeyListener false
                        when (keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                onAdjust(item.key, -1)
                                true
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                onAdjust(item.key, 1)
                                true
                            }
                            else -> false
                        }
                    }
                } else {
                    null
                },
            )
            itemView.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                if (focused) {
                    onFocused(item.key, view)
                    view.post {
                        if (view.hasFocus()) {
                            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), false)
                        }
                    }
                }
            }
        }

    }

    private class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.native_section_title)
        private val description: TextView = view.findViewById(R.id.native_section_description)

        fun bind(item: NativeRowModel) {
            title.setTextColor(NativeTheme.palette.text)
            description.setTextColor(NativeTheme.palette.secondaryText)
            itemView.isClickable = false
            title.text = item.title
            description.text = item.description
            description.visibility = if (item.description.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<NativeRowModel>() {
        override fun areItemsTheSame(oldItem: NativeRowModel, newItem: NativeRowModel): Boolean =
            oldItem.key == newItem.key

        override fun areContentsTheSame(oldItem: NativeRowModel, newItem: NativeRowModel): Boolean =
            oldItem == newItem
    }

    private companion object {
        val READING_ROW_KINDS = setOf(
            NativeRowKind.INFORMATION, NativeRowKind.VISUAL, NativeRowKind.DASHBOARD, NativeRowKind.SECTION,
        )
    }
}

private fun TextView.showColorIndicator(color: Int?) {
    if (color == null) {
        setCompoundDrawablesRelative(null, null, null, null)
        compoundDrawablePadding = 0
        return
    }
    val size = context.dp(11)
    val indicator = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(context.dp(1), NativeTheme.palette.divider)
        setBounds(0, 0, size, size)
    }
    setCompoundDrawablesRelative(indicator, null, null, null)
    compoundDrawablePadding = context.dp(8)
}
