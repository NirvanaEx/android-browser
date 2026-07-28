package com.upgrid.browser.ui

import android.view.View
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.core.view.isVisible

/**
 * How things move in this app.
 *
 * One file so that everything that appears, disappears or reacts does it at the
 * same speed and on the same curve. Animation read as cheap in this browser for
 * one reason: there wasn't any, so every change was a cut — a panel that is
 * there on one frame and gone on the next, a counter that teleports from 3 to
 * 4. The eye reads a cut as a glitch and movement as an object.
 *
 * Three rules the durations follow, and they are the platform's, not ours:
 *
 *  - **[QUICK] is for things that answer you.** A button, a badge, a chip. Long
 *    enough to be seen, short enough that it is over before you look for it.
 *  - **[STANDARD] is for things that arrive.** A panel, a page, a drop-down.
 *  - **Nothing here waits on anything.** Every animation is on a view's own
 *    property animator, so a second call cancels the first instead of queueing
 *    behind it; state is set immediately and the movement catches up.
 *
 * [EASE] is Material's standard curve — fast at the start, settling at the end,
 * which is how physical things stop. [OVERSHOOT_EASE] goes a hair past its
 * target and comes back; it belongs only on things that were *pushed*.
 */
object Motion {

    /** Something answering a touch. */
    const val QUICK = 130L

    /** Something arriving or leaving. */
    const val STANDARD = 200L

    /** Material's standard easing. */
    val EASE = PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f)

    /** Emphasised — decelerates harder. For things that come to rest. */
    val EASE_OUT = PathInterpolatorCompat.create(0f, 0f, 0f, 1f)
}

/**
 * Show or hide, as a movement rather than a cut.
 *
 * Idempotent in both directions, including *mid-animation*: asking for a view
 * that is fading out to come back cancels the fade and brings it to full
 * opacity rather than returning early, which is what a plain
 * `visible == isVisible` guard would do — that one leaves a view marked
 * visible and stuck at alpha 0.
 *
 * @param rise how far, in pixels, the view travels up into place. 0 for a
 *   straight fade; a small positive number for a panel that should read as
 *   coming from somewhere.
 */
fun View.setVisibleAnimated(
    visible: Boolean,
    duration: Long = Motion.STANDARD,
    rise: Float = 0f,
) {
    if (visible && isVisible && alpha == 1f && translationY == 0f) return
    if (!visible && !isVisible) return

    animate().cancel()
    if (visible) {
        if (!isVisible) {
            alpha = 0f
            translationY = rise
            isVisible = true
        }
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(Motion.EASE_OUT)
            .start()
    } else {
        animate()
            .alpha(0f)
            .translationY(rise)
            .setDuration(duration)
            .setInterpolator(Motion.EASE)
            .withEndAction {
                isVisible = false
                alpha = 1f
                translationY = 0f
            }
            .start()
    }
}

/**
 * A short push outwards and back — for a value that changed on its own.
 *
 * The tab counter is the case this exists for: the number changes because a tab
 * opened somewhere else on the screen, and without the bump nothing connects
 * the two events.
 */
fun View.bump(peak: Float = 1.18f) {
    animate().cancel()
    scaleX = 1f
    scaleY = 1f
    animate()
        .scaleX(peak)
        .scaleY(peak)
        .setDuration(Motion.QUICK)
        .setInterpolator(Motion.EASE)
        .withEndAction {
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(Motion.STANDARD)
                .setInterpolator(Motion.EASE_OUT)
                .start()
        }
        .start()
}
