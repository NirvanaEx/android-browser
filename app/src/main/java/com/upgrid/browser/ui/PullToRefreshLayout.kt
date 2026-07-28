package com.upgrid.browser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import mozilla.components.concept.engine.EngineView

/**
 * The page's refresh layout, with the gesture given back to the user.
 *
 * Plain [SwipeRefreshLayout] plus GeckoView is a combination that works only on
 * one kind of page. The engine claims every gesture up front — on ACTION_DOWN
 * `NestedGeckoView` calls `requestDisallowInterceptTouchEvent(true)` and starts
 * a nested scroll — and then hands it back only after Gecko has answered, and
 * only when the answer is "I am panning a long document that is already at its
 * top" (`INPUT_HANDLED` + can-overscroll-top). Two very ordinary answers never
 * release it:
 *
 *  - `INPUT_UNHANDLED` — the page is not taller than the screen, so there is
 *    nothing to pan. Short pages simply could not be pulled.
 *  - `INPUT_HANDLED_CONTENT` — the site has its own touch listeners, which is
 *    most of the modern web.
 *
 * With the gesture claimed and the nested scroll started, neither route into
 * [SwipeRefreshLayout] is open: `onInterceptTouchEvent` is skipped because
 * interception was disallowed, and the nested-scroll path is dead because
 * `NestedGeckoView` only forwards scrolls it is itself performing. The pull did
 * nothing, silently, and looked like a missing feature.
 *
 * So: at the very top of the page the pull is ours. [pageAtTop] is sampled once
 * per gesture, at ACTION_DOWN, from the engine's own scroll position — not from
 * the async input result, which is the thing that isn't reliable here. While it
 * holds we refuse the nested-scroll handshake and ignore the engine's request
 * not to intercept, which is enough for [SwipeRefreshLayout] to do what it
 * already knows how to do.
 *
 * What this gives up: on a page that is scrolled to the top *and* wants to
 * handle a downward drag itself (a canvas, a map, a custom pull-down), the
 * refresh wins. That is the same trade Chrome makes, and it is the one the
 * gesture is named after.
 */
class PullToRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {

    /**
     * The page being refreshed. Set by MainActivity once, next to the feature
     * that owns the reload; without it this behaves exactly like the base class.
     */
    var engineView: EngineView? = null

    /**
     * Whether the page had nothing above it when the current gesture started.
     *
     * Sampled at ACTION_DOWN rather than read live: it decides who owns the
     * whole gesture, and a mid-drag change of mind produces exactly the kind of
     * half-scrolled, half-refreshed state this is meant to avoid.
     */
    private var pageAtTop = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pageAtTop = engineView?.canScrollVerticallyUp() == false
        }
        return super.dispatchTouchEvent(event)
    }

    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        // The engine asks for this on every ACTION_DOWN, before it knows what
        // the gesture is. At the top of the page there is no upward scroll for
        // it to protect, so the request is about a gesture it isn't going to
        // use.
        if (disallow && pageAtTop) return
        super.requestDisallowInterceptTouchEvent(disallow)
    }

    override fun onStartNestedScroll(child: View, target: View, nestedScrollAxes: Int): Boolean {
        // Accepting would set mNestedScrollInProgress, and that alone makes
        // onInterceptTouchEvent return false for the rest of the gesture.
        // Refusing costs nothing: the nested-scroll dance exists for collapsing
        // toolbars inside a CoordinatorLayout, which this screen doesn't have,
        // and the page still pans through Gecko either way.
        if (pageAtTop) return false
        return super.onStartNestedScroll(child, target, nestedScrollAxes)
    }
}
