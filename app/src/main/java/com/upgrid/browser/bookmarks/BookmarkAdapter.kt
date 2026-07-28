package com.upgrid.browser.bookmarks

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.upgrid.browser.databinding.ItemBookmarkRowBinding
import com.upgrid.browser.ui.HostTile

/**
 * Flat list of saved pages, newest first.
 *
 * No day headers, unlike history: bookmarks are looked up by site, not by when
 * they were saved, and grouping a list of thirty by date just adds thirty
 * headers.
 */
class BookmarkAdapter(
    private val onOpen: (Bookmark) -> Unit,
    private val onDelete: (Bookmark) -> Unit,
) : RecyclerView.Adapter<BookmarkAdapter.Holder>() {

    private var items: List<Bookmark> = emptyList()

    fun submit(bookmarks: List<Bookmark>) {
        items = bookmarks
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemBookmarkRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemBookmarkRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(bookmark: Bookmark) = with(binding) {
            bookmarkTitle.text =
                bookmark.title.ifBlank { bookmark.host.ifBlank { bookmark.url } }
            bookmarkUrl.text =
                bookmark.url.removePrefix("https://").removePrefix("http://")

            bookmarkInitial.text = HostTile.letterFor(bookmark.host)
            bookmarkInitial.backgroundTintList =
                ColorStateList.valueOf(HostTile.colorFor(bookmark.host))

            root.setOnClickListener { onOpen(bookmark) }
            btnDeleteBookmark.setOnClickListener { onDelete(bookmark) }
        }
    }
}
