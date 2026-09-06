package app.opah.tv.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.opah.tv.R

internal class NativeRootLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private lateinit var navigationRail: LinearLayout
    private lateinit var contentHost: FrameLayout
    private var selectedNavigationView: View? = null
    private var lastContentFocus: View? = null

    var navigationOpen: Boolean = false
        private set

    var onBackFromContent: (() -> Unit)? = null
    var onNavigationOpened: (() -> Unit)? = null
    var onNavigationStateChanged: ((Boolean) -> Unit)? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        navigationRail = findViewById(R.id.native_nav_rail)
        contentHost = findViewById(R.id.native_content_host)
    }

    fun setSelectedNavigationView(view: View?) {
        selectedNavigationView = view
    }

    fun rememberContentFocus(view: View?) {
        if (view != null && contentHost.isAncestorOf(view)) lastContentFocus = view
    }

    fun openNavigation() {
        if (navigationOpen) return
        rememberContentFocus(findFocus())
        navigationOpen = true
        navigationRail.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        navigationRail.layoutParams = navigationRail.layoutParams.apply { width = context.dp(152) }
        onNavigationStateChanged?.invoke(true)
        onNavigationOpened?.invoke()
        selectedNavigationView?.requestFocus()
    }

    fun closeNavigation(restoreContentFocus: Boolean = true) {
        if (!navigationOpen) return
        navigationOpen = false
        navigationRail.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        navigationRail.layoutParams = navigationRail.layoutParams.apply { width = context.dp(56) }
        onNavigationStateChanged?.invoke(false)
        if (restoreContentFocus) {
            val remembered = lastContentFocus
            if (remembered?.isAttachedToWindow == true && remembered.isFocusable) {
                remembered.requestFocus()
            } else {
                contentHost.focusSearch(View.FOCUS_DOWN)?.requestFocus()
            }
        }
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        val proposed = super.focusSearch(focused, direction)
        val focusedInContent = focused != null && contentHost.isAncestorOf(focused)
        val proposedInNavigation = proposed != null && navigationRail.isAncestorOf(proposed)
        return if (shouldKeepFocusInContent(direction, focusedInContent, proposedInNavigation)) {
            focused
        } else {
            proposed
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (navigationOpen) closeNavigation() else openNavigation()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (navigationOpen) {
                    closeNavigation()
                } else if (!super.dispatchKeyEvent(event)) {
                    onBackFromContent?.invoke()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (navigationOpen) {
                closeNavigation()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val focused = findFocus()
                if (!navigationOpen && focused != null && contentHost.isAncestorOf(focused)) {
                    val next = FocusFinder.getInstance().findNextFocus(contentHost, focused, View.FOCUS_LEFT)
                    if (next == null || next === focused) {
                        openNavigation()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

private fun ViewGroup.isAncestorOf(view: View): Boolean {
    var parent = view.parent
    while (parent is View) {
        if (parent === this) return true
        parent = parent.parent
    }
    return false
}
