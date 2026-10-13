package app.opah.tv.ui.views

import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.RecyclerView
import app.opah.tv.R
import app.opah.tv.data.model.Camera

/** One dialog owns both steps, so returning from naming never loses the selection. */
internal fun showNativeCameraViewDialog(
    activity: ComponentActivity,
    dialog: Dialog,
    cameras: List<Camera>,
    onSave: (String, List<String>) -> Unit,
) {
    val selected = linkedSetOf<String>()
    var naming = false
    var submitted = false
    var draftName = ""
    var nameInput: EditText? = null
    val container = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(22), activity.dp(18), activity.dp(22), activity.dp(18))
        background = activity.nativeGlassPanelBackground(strong = true)
    }
    fun label(value: String, size: Float = 15f) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(NativeTheme.palette.text)
        setPadding(0, activity.dp(4), 0, activity.dp(8))
    }
    fun button(value: String, action: () -> Unit) = TextView(activity).apply {
        text = value; textSize = 16f; gravity = Gravity.CENTER
        setTextColor(NativeTheme.palette.text)
        background = activity.nativeFlatFocusableBackground()
        isFocusable = true; isClickable = true
        setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10))
        setOnClickListener { action() }
    }
    lateinit var showChoices: () -> Unit
    fun showName() {
        naming = true
        container.removeAllViews()
        container.addView(label("Name this camera view", 22f))
        container.addView(label(cameras.filter { it.name in selected }.joinToString(" • ") { it.displayName }))
        val input = EditText(activity).apply {
            setSingleLine(true); setText(draftName); setSelection(text.length)
            hint = "For example, Outside"
            setTextColor(NativeTheme.palette.text); setHintTextColor(NativeTheme.palette.secondaryText)
            background = activity.nativeFlatFieldBackground()
            setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
        }
        nameInput = input
        container.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(LinearLayout(activity).apply {
            gravity = Gravity.END
            addView(button("Back to cameras") { draftName = input.text.toString(); showChoices() })
            addView(button("Save view") {
                if (submitted || !dialog.isShowing) return@button
                val name = input.text.toString().trim()
                if (name.isBlank()) { input.error = "Enter a name"; input.requestFocus() }
                else { submitted = true; dialog.dismiss(); onSave(name, selected.toList()) }
            })
        })
        input.requestFocus()
        input.post { (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }
    showChoices = {
        naming = false
        nameInput?.let { (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }
        nameInput = null
        container.removeAllViews()
        container.addView(label("Create camera view", 22f))
        val count = label("")
        container.addView(count)
        val list = RecyclerView(activity).apply {
            layoutManager = NativeLinearLayoutManager(activity); itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val next = button("Next: name this view") { showName() }
        lateinit var adapter: NativeListAdapter
        fun update() {
            count.text = activity.resources.getQuantityString(R.plurals.native_camera_selection_count, selected.size, selected.size)
            next.isEnabled = selected.size in 2..4
            next.alpha = if (next.isEnabled) 1f else 0.42f
            adapter.submitList(cameras.map { camera -> NativeRowModel(
                key = camera.name, title = camera.displayName,
                description = if (camera.name in selected) "Included in this view" else "Not included",
                kind = NativeRowKind.CHOICE, selected = camera.name in selected,
                enabled = camera.name in selected || selected.size < 4,
            ) })
        }
        adapter = NativeListAdapter(onActivate = { key ->
            if (key in selected) selected.remove(key) else if (selected.size < 4) selected.add(key)
            update()
        }, onFocused = { _, _ -> })
        list.adapter = adapter
        container.addView(list, LinearLayout.LayoutParams(activity.dp(640), activity.dp(nativeDialogListHeightDp(cameras.size).coerceAtMost(260))))
        container.addView(LinearLayout(activity).apply {
            gravity = Gravity.END
            addView(button("Cancel") { dialog.dismiss() })
            addView(next)
        })
        update()
        list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }
    dialog.setContentView(container)
    dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
    dialog.setOnKeyListener { _, key, event ->
        if (key != KeyEvent.KEYCODE_BACK || !naming) false else {
            if (event.action == KeyEvent.ACTION_UP) {
                draftName = nameInput?.text?.toString().orEmpty()
                showChoices()
            }
            true
        }
    }
    dialog.setOnShowListener {
        dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        showChoices()
    }
    dialog.show()
}
