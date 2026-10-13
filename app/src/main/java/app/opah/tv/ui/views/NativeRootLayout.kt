package app.opah.tv.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.isVisible
import app.opah.tv.R

internal class NativeRootLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private lateinit var navigationRail: LinearLayout
    private lateinit var contentHost: FrameLayout
    private var selectedNavigationView: View? = null
    private var lastContentFocus: View? = null
    private var consumedRootKey: Int? = null
    private var consumedRootDownTime = 0L
    private var contentFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

    var navigationOpen: Boolean = false
        private set

    var onBackFromContent: (() -> Unit)? = null
    var onNavigationOpened: (() -> Unit)? = null
    var onNavigationStateChanged: ((Boolean) -> Unit)? = null
    var onRestoreContentFocus: (() -> Boolean)? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        navigationRail = findViewById(R.id.native_nav_rail)
        contentHost = findViewById(R.id.native_content_host)
        navigationRail.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    fun setSelectedNavigationView(view: View?) {
        selectedNavigationView = view
    }

    fun rememberContentFocus(view: View?) {
        if (view != null && contentHost.isAncestorOf(view)) lastContentFocus = view
    }

    fun openNavigation() {
        if (navigationOpen || navigationRail.visibility != View.VISIBLE) return
        rememberContentFocus(findFocus())
        navigationOpen = true
        contentFocusability = contentHost.descendantFocusability
        contentHost.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        navigationRail.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        navigationRail.layoutParams = navigationRail.layoutParams.apply { width = context.dp(172) }
        animateRailEntrance()
        onNavigationStateChanged?.invoke(true)
        onNavigationOpened?.invoke()
        selectedNavigationView?.requestFocus()
    }

    fun closeNavigation(restoreContentFocus: Boolean = true) {
        if (!navigationOpen) return
        navigationOpen = false
        contentHost.descendantFocusability = contentFocusability
        navigationRail.animate().cancel()
        navigationRail.translationX = 0f
        navigationRail.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        navigationRail.layoutParams = navigationRail.layoutParams.apply { width = context.dp(56) }
        onNavigationStateChanged?.invoke(false)
        if (restoreContentFocus) {
            val remembered = lastContentFocus
            val restored = remembered?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled && it.isFocusable }
                ?.requestFocus() == true
            if (!restored && onRestoreContentFocus?.invoke() != true) {
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
        if (event.keyCode == consumedRootKey) {
            val newPress = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                event.downTime != consumedRootDownTime
            if (newPress) {
                // Android may finish Back through its system callback without
                // delivering key-up here. A later physical press must still work.
                consumedRootKey = null
            } else {
                if (event.action == KeyEvent.ACTION_UP && event.downTime == consumedRootDownTime) {
                    consumedRootKey = null
                }
                return true
            }
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (event.repeatCount > 0) return true
                consumeRootPress(event)
                if (navigationOpen) closeNavigation() else openNavigation()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (event.repeatCount > 0) return true
                consumeRootPress(event)
                if (navigationOpen) {
                    closeNavigation()
                } else if (!super.dispatchKeyEvent(event)) {
                    onBackFromContent?.invoke()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (navigationOpen) {
                consumeRootPress(event)
                closeNavigation()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> if (navigationOpen) {
                val focused = findFocus()
                if (focused != null && navigationRail.isAncestorOf(focused)) {
                    focusNavigationSibling(
                        focused,
                        forward = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN,
                    )
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (navigationOpen) return true
                // Sliders, timelines, editors and grid children get first refusal.
                if (super.dispatchKeyEvent(event)) return true
                val focused = findFocus()
                if (!navigationOpen && focused != null && contentHost.isAncestorOf(focused)) {
                    val next = FocusFinder.getInstance().findNextFocus(contentHost, focused, View.FOCUS_LEFT)
                    if ((next == null || next === focused) && event.repeatCount == 0) {
                        consumeRootPress(event)
                        openNavigation()
                        return true
                    }
                }
                return false
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun consumeRootPress(event: KeyEvent) {
        consumedRootKey = event.keyCode
        consumedRootDownTime = event.downTime
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) consumedRootKey = null
    }

    private fun animateRailEntrance() {
        navigationRail.animate().cancel()
        if (NativeTheme.reducedMotion) {
            navigationRail.translationX = 0f
            return
        }
        navigationRail.translationX = -context.dp(6).toFloat()
        navigationRail.animate().translationX(0f).setDuration(180L).start()
    }

    private fun focusNavigationSibling(focused: View, forward: Boolean): Boolean {
        val parent = focused.parent as? ViewGroup ?: return false
        val currentIndex = parent.indexOfChild(focused)
        if (currentIndex < 0) return false
        val positions = if (forward) {
            (currentIndex + 1) until parent.childCount
        } else {
            currentIndex - 1 downTo 0
        }
        val target = positions
            .map(parent::getChildAt)
            .firstOrNull { it.isFocusable && it.isEnabled && it.isVisible }
        return target?.requestFocus() == true
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
