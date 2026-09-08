package app.opah.tv.ui.views

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.opah.tv.R

internal fun RecyclerView.addNativeFlatDividers(startInsetDp: Int = 12) {
    addItemDecoration(NativeFlatDividerDecoration(context.dp(startInsetDp)))
}

internal fun RecyclerView.focusRow(position: Int): Boolean {
    if (position < 0 || position >= (adapter?.itemCount ?: 0)) return false
    scrollToPosition(position)
    post { findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
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
    val target = adjacentFocusableRowPosition(rowAdapter.currentList, currentPosition, forward) ?: return false
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
) : ListAdapter<NativeRowModel, RecyclerView.ViewHolder>(DiffCallback) {
    private val viewIds = mutableMapOf<String, Int>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = stableNativeItemId(getItem(position).key)

    override fun getItemViewType(position: Int): Int = getItem(position).kind.ordinal

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
                onActivate = onActivate,
                onFocused = onFocused,
                onAdjust = onAdjust,
                onThumbnailRequested = onThumbnailRequested,
            )
        }
    }

    private class InformationHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.native_information_title)
        private val description: TextView = view.findViewById(R.id.native_information_description)
        private val value: TextView = view.findViewById(R.id.native_information_value)

        fun bind(item: NativeRowModel) {
            val palette = NativeTheme.palette
            itemView.background = null
            itemView.isFocusable = false
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
            itemView.isFocusable = false
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
            metrics.removeAllViews()
            item.dashboardMetrics.forEachIndexed { index, metric ->
                if (index > 0) {
                    metrics.addView(
                        View(itemView.context).apply { setBackgroundColor(NativeTheme.palette.divider) },
                        LinearLayout.LayoutParams(itemView.context.dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                            marginStart = itemView.context.dp(10)
                            marginEnd = itemView.context.dp(10)
                        },
                    )
                }
                metrics.addView(
                    LinearLayout(itemView.context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            View(itemView.context).apply { setBackgroundColor(metric.color) },
                            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, itemView.context.dp(3)),
                        )
                        addView(
                            TextView(itemView.context).apply {
                                text = metric.value
                                textSize = 23f
                                setTextColor(NativeTheme.palette.text)
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                                setPadding(0, itemView.context.dp(7), 0, 0)
                            },
                        )
                        addView(
                            TextView(itemView.context).apply {
                                text = metric.label
                                textSize = 12f
                                setTextColor(NativeTheme.palette.secondaryText)
                            },
                        )
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                )
            }
            itemView.contentDescription = item.dashboardMetrics.joinToString(", ") { "${it.label} ${it.value}" }
            itemView.isFocusable = false
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

    private fun viewId(key: String): Int = viewIds.getOrPut(key, View::generateViewId)

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumbnail: ImageView = view.findViewById(R.id.native_row_thumbnail)
        private val title: TextView = view.findViewById(R.id.native_row_title)
        private val description: TextView = view.findViewById(R.id.native_row_description)
        private val value: TextView = view.findViewById(R.id.native_row_value)
        private val affordance: TextView = view.findViewById(R.id.native_row_affordance)

        fun bind(
            item: NativeRowModel,
            viewId: Int,
            onActivate: (String) -> Unit,
            onFocused: (String, View) -> Unit,
            onAdjust: (String, Int) -> Unit,
            onThumbnailRequested: (NativeRowModel, ImageView) -> Unit,
        ) {
            val palette = NativeTheme.palette
            itemView.background = itemView.context.nativeFlatFocusableBackground()
            thumbnail.clipToOutline = true
            thumbnail.background = itemView.context.nativeFlatThumbnailBackground()
            title.setTextColor(palette.text)
            description.setTextColor(palette.secondaryText)
            value.setTextColor(palette.secondaryText)
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
                NativeRowKind.TOGGLE -> if (item.selected) "On  ●" else "Off  ○"
                NativeRowKind.CHOICE -> if (item.selected) "●  Selected" else "○  Choose"
                NativeRowKind.ADJUSTMENT -> "◀  Change  ▶"
                NativeRowKind.READING -> "Read"
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
                affordance.tag = item.key
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
                affordance.tag = null
                affordance.isFocusable = false
                affordance.isClickable = false
                affordance.background = null
                affordance.setPadding(0, 0, 0, 0)
                affordance.setOnClickListener(null)
                affordance.onFocusChangeListener = null
            }
            thumbnail.setImageDrawable(null)
            thumbnail.tag = item.thumbnail?.cacheKey
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
            itemView.isFocusable = false
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
