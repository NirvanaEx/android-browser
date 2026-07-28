package com.upgrid.browser.privacy

import android.app.Activity
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.upgrid.browser.R
import com.upgrid.browser.databinding.DialogClearDataBinding
import kotlinx.coroutines.launch

/**
 * "Clear browsing data": pick a period, tick what to delete.
 *
 * Both lists are generated from [BrowsingDataCleaner]'s enums rather than
 * written out in XML, so adding a data type is one line in one file.
 *
 * The note under the checkboxes is not decoration: for anything but "all time"
 * the engine can only clear site data per host, and the wording says so. A
 * privacy control that quietly does less than it claims is worse than one that
 * explains itself.
 */
object ClearDataDialog {

    fun show(activity: Activity, cleaner: BrowsingDataCleaner, onDone: () -> Unit = {}) {
        val body = DialogClearDataBinding.inflate(LayoutInflater.from(activity))

        BrowsingDataCleaner.Period.entries.forEach { period ->
            body.periodGroup.addView(
                Chip(activity).apply {
                    id = android.view.View.generateViewId()
                    text = activity.getString(period.label)
                    isCheckable = true
                    isChecked = period == BrowsingDataCleaner.Period.DAY
                    tag = period
                },
            )
        }

        BrowsingDataCleaner.DataType.entries.forEach { type ->
            body.typeGroup.addView(
                MaterialCheckBox(activity).apply {
                    id = android.view.View.generateViewId()
                    text = activity.getString(type.label)
                    textSize = 14f
                    // History, cookies and cache are what "clear my browsing"
                    // means; searches and passwords are separate decisions and
                    // deleting a password by accident is unrecoverable.
                    isChecked = type in DEFAULT_TYPES
                    tag = type
                },
            )
        }

        body.clearNote.isVisible = true

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.clear_title)
            .setIcon(R.drawable.ic_delete)
            .setView(body.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear_confirm) { _, _ ->
                val period = body.periodGroup.checkedChipId
                    .let { body.periodGroup.findViewById<Chip>(it) }
                    ?.tag as? BrowsingDataCleaner.Period
                    ?: BrowsingDataCleaner.Period.DAY

                val types = (0 until body.typeGroup.childCount)
                    .mapNotNull { body.typeGroup.getChildAt(it) as? MaterialCheckBox }
                    .filter { it.isChecked }
                    .mapNotNull { it.tag as? BrowsingDataCleaner.DataType }
                    .toSet()

                if (types.isEmpty()) return@setPositiveButton

                val owner = activity as? LifecycleOwner ?: return@setPositiveButton
                Toast.makeText(activity, R.string.clear_running, Toast.LENGTH_SHORT).show()
                owner.lifecycleScope.launch {
                    cleaner.clear(period, types)
                    Toast.makeText(activity, R.string.clear_done, Toast.LENGTH_SHORT).show()
                    onDone()
                }
            }
            .show()
    }

    private val DEFAULT_TYPES = setOf(
        BrowsingDataCleaner.DataType.HISTORY,
        BrowsingDataCleaner.DataType.COOKIES,
        BrowsingDataCleaner.DataType.CACHE,
    )
}
