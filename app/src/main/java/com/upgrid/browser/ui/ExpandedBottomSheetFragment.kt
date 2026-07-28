package com.upgrid.browser.ui

import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * A bottom sheet that opens at full height instead of half-open.
 *
 * Every sheet in this app is a destination the user asked for by name — tabs,
 * history, bookmarks, settings. The default collapsed state shows the header
 * and about one and a half rows, so each of them started with a drag before it
 * was usable. Expanding on open removes that step.
 *
 * `skipCollapsed` matters as much as the initial state: without it a downward
 * drag parks the sheet at the peek height instead of dismissing, and the user
 * has to swipe twice to get back to the page.
 */
abstract class ExpandedBottomSheetFragment : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        // Configured in onStart, not onCreateDialog: the behavior is attached to
        // the sheet's container view, which doesn't exist until the dialog has
        // laid out its content.
        val behavior = (dialog as? BottomSheetDialog)?.behavior ?: return
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}
