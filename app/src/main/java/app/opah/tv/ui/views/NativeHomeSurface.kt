package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.opah.tv.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class NativeHomeTileKind { CAMERA, CAMERA_GROUP, SAVED_VIEW, BIRDSEYE }

internal data class NativeHomeTile(
    val key: String,
    val title: String,
    val subtitle: String,
    val kind: NativeHomeTileKind,
    val sourceId: String? = null,
    val previewCameraName: String? = null,
    val cameraNames: List<String> = emptyList(),
    val favorite: Boolean = false,
)

internal data class NativeHomeQuickAction(
    val key: String,
    val title: String,
    val detail: String,
    val iconRes: Int,
    val accentColor: Int,
    val value: String = "Open",
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

internal data class NativeHomeUiState(
    val subtitle: String,
    val tiles: List<NativeHomeTile>,
    val quickActions: List<NativeHomeQuickAction>,
)

@SuppressLint("ViewConstructor", "SetTextI18n")
internal class NativeHomeSurface(
    private val activity: ComponentActivity,
    initialState: NativeHomeUiState,
    private val cachedBitmap: (String) -> Bitmap?,
    private val refreshBitmap: suspend (String, Int) -> Bitmap?,
    private val onPrimary: (NativeHomeTile) -> Unit,
    private val onSecondary: (NativeHomeTile) -> Unit,
    private val onOptions: (NativeHomeTile) -> Unit,
) : LinearLayout(activity) {
    private val subtitleView = text(15f, secondary = true)
    private val heroImage = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(NativeTheme.palette.panel)
    }
    private val heroTitle = text(21f, bold = true).apply { setTextColor(Color.WHITE) }
    private val heroDetail = text(12f, secondary = true).apply { setTextColor(0xFFD7E1EE.toInt()) }
    private val primaryButton = button("Open") { selectedTile?.let(onPrimary) }
    private val secondaryButton = button("More") { selectedTile?.let(onSecondary) }
    private val optionsButton = button("Options") { selectedTile?.let(onOptions) }
    private val quickItems = GridLayout(activity).apply {
        columnCount = 2
        alignmentMode = GridLayout.ALIGN_BOUNDS
        useDefaultMargins = false
    }
    private val quickDetail = text(11.5f, secondary = true).apply {
        maxLines = 3
        setPadding(activity.dp(9), activity.dp(5), activity.dp(7), activity.dp(4))
    }
    private val quickActionViews = mutableMapOf<String, View>()
    private var selectedQuickActionKey: String? = null
    private val quickPanel = LinearLayout(activity).apply {
        orientation = VERTICAL
        setPadding(activity.dp(10), activity.dp(2), 0, 0)
        setBackgroundColor(Color.TRANSPARENT)
        addView(text(17f, bold = true).apply {
            text = "At a glance"
            setPadding(activity.dp(9), activity.dp(2), activity.dp(6), activity.dp(3))
        })
        addView(
            ScrollView(activity).apply {
                isFocusable = false
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                setPadding(0, 0, activity.dp(2), activity.dp(4))
                addView(
                    quickItems,
                    FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        addView(quickDetail, LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(48)))
    }
    private val tilesView = RecyclerView(activity).apply {
        layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding(0, 0, activity.dp(22), activity.dp(8))
    }
    private val tileAdapter = HomeTileAdapter(
        onFocused = ::selectTile,
        onActivated = { tile -> selectTile(tile); onPrimary(tile) },
        onImage = ::loadTileImage,
    )
    private var selectedTile: NativeHomeTile? = null
    private var heroImageJob: Job? = null
    private val tileImageJobs = mutableMapOf<String, Job>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        isFocusable = false

        addView(
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    LinearLayout(activity).apply {
                        orientation = VERTICAL
                        addView(text(31f, bold = true).apply { text = "Home" })
                        addView(subtitleView)
                    },
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        val hero = FrameLayout(activity).apply {
            setBackgroundColor(NativeTheme.palette.panel)
            addView(heroImage, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(
                LinearLayout(activity).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(activity.dp(12), activity.dp(7), activity.dp(10), activity.dp(7))
                    setBackgroundColor(0x9207111F.toInt())
                    addView(
                        LinearLayout(activity).apply {
                            orientation = VERTICAL
                            addView(heroTitle)
                            addView(heroDetail)
                        },
                        LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        LinearLayout(activity).apply {
                            gravity = Gravity.START
                            addView(primaryButton)
                            addView(secondaryButton)
                            addView(optionsButton)
                        },
                    )
                },
                FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
            )
        }
        addView(
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(hero, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = activity.dp(7) })
                addView(
                    View(activity).apply { setBackgroundColor(NativeTheme.palette.divider) },
                    LayoutParams(activity.dp(1), LayoutParams.MATCH_PARENT).apply { marginEnd = activity.dp(3) },
                )
                addView(quickPanel, LayoutParams(activity.dp(312), LayoutParams.MATCH_PARENT))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = activity.dp(12)
                bottomMargin = activity.dp(12)
            },
        )

        addView(text(18f, bold = true).apply { text = "Cameras and views" })
        tilesView.adapter = tileAdapter
        addView(tilesView, LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(202)).apply { topMargin = activity.dp(6) })
        update(initialState)
    }

    fun update(state: NativeHomeUiState) {
        subtitleView.text = state.subtitle
        val selectedKey = selectedTile?.key
        tileAdapter.submitList(state.tiles) {
            val next = state.tiles.firstOrNull { it.key == selectedKey } ?: state.tiles.firstOrNull()
            if (next != null) selectTile(next)
            if (tilesView.findFocus() == null && !hasFocus()) {
                tilesView.post { tilesView.findViewHolderForAdapterPosition(state.tiles.indexOf(next))?.itemView?.requestFocus() }
            }
        }
        updateQuickActions(state.quickActions)
    }

    fun close() {
        heroImageJob?.cancel()
        tileImageJobs.values.forEach(Job::cancel)
        tileImageJobs.clear()
    }

    private fun selectTile(tile: NativeHomeTile) {
        selectedTile = tile
        tileAdapter.selectedKey = tile.key
        heroTitle.text = tile.title
        heroDetail.text = tile.subtitle
        primaryButton.text = when (tile.kind) {
            NativeHomeTileKind.CAMERA, NativeHomeTileKind.BIRDSEYE -> "Play"
            NativeHomeTileKind.CAMERA_GROUP, NativeHomeTileKind.SAVED_VIEW -> "Open view"
        }
        val supportsMonitor = tile.kind == NativeHomeTileKind.CAMERA_GROUP || tile.kind == NativeHomeTileKind.SAVED_VIEW
        secondaryButton.visibility = if (supportsMonitor || tile.kind == NativeHomeTileKind.CAMERA) View.VISIBLE else View.GONE
        secondaryButton.text = if (supportsMonitor) "Monitor" else if (tile.favorite) "Unfavorite" else "Favorite"
        optionsButton.visibility = if (tile.kind == NativeHomeTileKind.BIRDSEYE) View.GONE else View.VISIBLE
        loadHero(tile.previewCameraName)
    }

    private fun updateQuickActions(actions: List<NativeHomeQuickAction>) {
        val focusedKey = quickItems.findFocus()?.tag as? String
        selectedQuickActionKey = selectedQuickActionKey
            ?.takeIf { selected -> actions.any { it.key == selected } }
            ?: actions.firstOrNull { it.selected }?.key
            ?: actions.firstOrNull()?.key
        quickItems.removeAllViews()
        quickActionViews.clear()
        actions.forEachIndexed { index, action ->
            val actionView =
                LinearLayout(activity).apply {
                    tag = action.key
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isEnabled = action.enabled
                    isFocusable = action.enabled
                    isClickable = action.enabled
                    isSelected = action.key == selectedQuickActionKey
                    alpha = if (action.enabled) 1f else 0.48f
                    contentDescription = buildString {
                        append(action.title)
                        if (action.detail.isNotBlank()) append(", ${action.detail}")
                        if (action.value.isNotBlank()) append(", ${action.value}")
                        if (!action.enabled) append(", unavailable while Opah finishes the current action")
                    }
                    background = activity.nativeFlatFocusableBackground()
                    setPadding(activity.dp(7), activity.dp(6), activity.dp(5), activity.dp(6))
                    addView(
                        ImageView(activity).apply {
                            setImageResource(action.iconRes)
                            drawable?.setTint(action.accentColor)
                            contentDescription = null
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            scaleType = ImageView.ScaleType.CENTER_INSIDE
                        },
                        LayoutParams(activity.dp(28), activity.dp(28)).apply {
                            marginEnd = activity.dp(6)
                            gravity = Gravity.CENTER_VERTICAL
                        },
                    )
                    addView(
                        LinearLayout(activity).apply {
                            orientation = VERTICAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(text(12.5f, bold = true).apply {
                                text = action.title
                                maxLines = 2
                            })
                            addView(text(11f).apply {
                                text = action.value
                                setTextColor(action.accentColor)
                                maxLines = 1
                            })
                        },
                        LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
                    )
                    setOnClickListener(if (action.enabled) View.OnClickListener { action.onClick() } else null)
                    onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                        if (focused) {
                            selectQuickAction(action)
                            view.post {
                                if (view.hasFocus()) {
                                    view.requestRectangleOnScreen(
                                        android.graphics.Rect(0, 0, view.width, view.height),
                                        false,
                                    )
                                }
                            }
                        }
                    }
                }
            quickActionViews[action.key] = actionView
            quickItems.addView(
                actionView,
                GridLayout.LayoutParams(
                    GridLayout.spec(index / 2),
                    GridLayout.spec(index % 2, 1f),
                ).apply {
                    width = 0
                    height = activity.dp(62)
                    setMargins(0, 0, activity.dp(3), activity.dp(3))
                },
            )
        }
        actions.firstOrNull { it.key == selectedQuickActionKey }?.let(::selectQuickAction)
        quickDetail.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
        if (focusedKey != null) quickItems.post { quickItems.findViewWithTag<View>(focusedKey)?.requestFocus() }
    }

    private fun selectQuickAction(action: NativeHomeQuickAction) {
        selectedQuickActionKey = action.key
        quickActionViews.forEach { (key, view) -> view.isSelected = key == action.key }
        quickDetail.text = action.detail
    }

    private fun loadHero(cameraName: String?) {
        heroImageJob?.cancel()
        heroImage.setImageDrawable(null)
        if (cameraName == null) return
        cachedBitmap(cameraName)?.let(heroImage::setImageBitmap)
        heroImageJob = activity.lifecycleScope.launch {
            refreshBitmap(cameraName, 900)?.let { bitmap ->
                if (selectedTile?.previewCameraName == cameraName) heroImage.setImageBitmap(bitmap)
            }
        }
    }

    private fun loadTileImage(tile: NativeHomeTile, target: ImageView) {
        val cameraName = tile.previewCameraName ?: return
        target.tag = tile.key
        cachedBitmap(cameraName)?.let { target.setImageBitmap(it); return }
        tileImageJobs[tile.key]?.cancel()
        tileImageJobs[tile.key] = activity.lifecycleScope.launch {
            refreshBitmap(cameraName, 180)?.let { bitmap ->
                if (target.tag == tile.key) target.setImageBitmap(bitmap)
            }
        }
    }

    private fun text(size: Float, bold: Boolean = false, secondary: Boolean = false) = TextView(activity).apply {
        textSize = size
        setTextColor(if (secondary) NativeTheme.palette.secondaryText else NativeTheme.palette.text)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, action: () -> Unit) = TextView(activity).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        background = activity.nativeFlatOverlayFocusableBackground()
        setPadding(activity.dp(12), activity.dp(5), activity.dp(12), activity.dp(5))
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, activity.dp(38)).apply { marginEnd = activity.dp(4) }
        setOnClickListener { action() }
    }

    private class HomeTileAdapter(
        private val onFocused: (NativeHomeTile) -> Unit,
        private val onActivated: (NativeHomeTile) -> Unit,
        private val onImage: (NativeHomeTile, ImageView) -> Unit,
    ) : ListAdapter<NativeHomeTile, TileHolder>(DIFF) {
        var selectedKey: String? = null
            set(value) { field = value }

        init { setHasStableIds(true) }

        override fun getItemId(position: Int): Long = stableNativeItemId(getItem(position).key)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder = TileHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.native_home_tile, parent, false),
        )

        override fun onBindViewHolder(holder: TileHolder, position: Int) {
            holder.bind(getItem(position), getItem(position).key == selectedKey, onFocused, onActivated, onImage)
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<NativeHomeTile>() {
                override fun areItemsTheSame(oldItem: NativeHomeTile, newItem: NativeHomeTile) = oldItem.key == newItem.key
                override fun areContentsTheSame(oldItem: NativeHomeTile, newItem: NativeHomeTile) = oldItem == newItem
            }
        }
    }

    private class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.native_home_tile_image)
        private val title: TextView = view.findViewById(R.id.native_home_tile_title)
        private val subtitle: TextView = view.findViewById(R.id.native_home_tile_subtitle)

        fun bind(
            tile: NativeHomeTile,
            selected: Boolean,
            onFocused: (NativeHomeTile) -> Unit,
            onActivated: (NativeHomeTile) -> Unit,
            onImage: (NativeHomeTile, ImageView) -> Unit,
        ) {
            itemView.background = itemView.context.nativeFlatFocusableBackground()
            image.setBackgroundColor(NativeTheme.palette.panel)
            title.setTextColor(NativeTheme.palette.text)
            subtitle.setTextColor(NativeTheme.palette.secondaryText)
            itemView.tag = tile.key
            itemView.isSelected = selected
            title.text = tile.title
            subtitle.text = tile.subtitle
            image.setImageDrawable(null)
            image.clipToOutline = false
            onImage(tile, image)
            itemView.setOnClickListener { onActivated(tile) }
            itemView.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                if (focused) {
                    onFocused(tile)
                    view.post {
                        if (view.hasFocus()) {
                            view.requestRectangleOnScreen(
                                android.graphics.Rect(0, 0, view.width, view.height),
                                false,
                            )
                        }
                    }
                }
            }
        }
    }
}
