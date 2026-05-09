package com.upgrid.browser.tabs

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ItemTabRowBinding
import mozilla.components.browser.state.state.TabSessionState

/**
 * One tab row in the tabs tray.
 *
 * For favicons we read the bitmap straight off [TabSessionState.content.icon]
 * — GeckoView's engine populates it on `<link rel=icon>` parse and the result
 * lives inside the BrowserStore, so it's free for us to consume here. We
 * deliberately skip [mozilla.components.browser.icons.BrowserIcons.loadIntoView]
 * because in a-c 150 it nulls the ImageView before its async fetch kicks off,
 * which produced an empty circle for any tab whose favicon hadn't loaded yet.
 *
 * When no bitmap is available we fall back to the globe vector and reapply the
 * onSurfaceVariant tint (setImageBitmap clears the previous tint state).
 */
class TabViewHolder private constructor(
    private val binding: ItemTabRowBinding,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(
        tab: TabSessionState,
        isSelected: Boolean,
        onClick: (TabSessionState) -> Unit,
        onClose: (TabSessionState) -> Unit,
    ) {
        binding.title.text = tab.content.title.ifBlank { tab.content.url }
        binding.url.text = tab.content.url
        binding.root.isSelected = isSelected
        binding.root.setOnClickListener { onClick(tab) }
        binding.btnClose.setOnClickListener { onClose(tab) }

        val bitmap = tab.content.icon
        if (bitmap != null) {
            binding.favicon.setImageBitmap(bitmap)
            // Real favicons are full-color — drop the placeholder tint.
            binding.favicon.imageTintList = null
        } else {
            binding.favicon.setImageResource(R.drawable.ic_globe)
            binding.favicon.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    binding.favicon,
                    MaterialR.attr.colorOnSurfaceVariant,
                    ContextCompat.getColor(binding.root.context, android.R.color.darker_gray),
                )
            )
        }
    }

    companion object {
        fun inflate(parent: ViewGroup): TabViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ItemTabRowBinding.inflate(inflater, parent, false)
            return TabViewHolder(binding)
        }
    }
}
