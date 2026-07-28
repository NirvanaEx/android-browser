package com.upgrid.browser.privacy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ActivitySiteDataBinding
import com.upgrid.browser.databinding.ItemSiteRowBinding
import com.upgrid.browser.history.HostEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.browser.icons.BrowserIcons
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which sites have data on this device.
 *
 * The list is built from browsing history, because the engine can delete a
 * host's cookies and storage but cannot enumerate the hosts that have any —
 * and every site with data is a site that was visited. Clearing a row wipes
 * that site's cookies, storage, caches and permissions, and forgets its pages.
 */
class SiteDataActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySiteDataBinding
    private val components get() = (application as BrowserApplication).components
    private val cleaner by lazy { BrowsingDataCleaner(applicationContext, components) }
    private lateinit var adapter: SitesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySiteDataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.pageTitle.setText(R.string.site_data_title)
        binding.header.btnBack.setOnClickListener { finish() }
        binding.header.btnHeaderAction.setText(R.string.site_data_clear_all)
        binding.header.btnHeaderAction.setOnClickListener {
            ClearDataDialog.show(this, cleaner) { render() }
        }

        adapter = SitesAdapter(
            icons = components.icons,
            scope = lifecycleScope,
            onDelete = ::confirmDelete,
        )
        binding.sitesList.layoutManager = LinearLayoutManager(this)
        binding.sitesList.adapter = adapter

        render()
    }

    private fun render() {
        lifecycleScope.launch {
            val hosts = components.browsingHistory.hosts()
            adapter.submit(hosts)
            binding.sitesList.isVisible = hosts.isNotEmpty()
            binding.emptyState.isVisible = hosts.isEmpty()
            binding.header.btnHeaderAction.isVisible = true

            val bytes = cleaner.approximateCacheBytes()
            binding.sizeLine.text = getString(
                R.string.site_data_size,
                Formatter.formatShortFileSize(this@SiteDataActivity, bytes),
            )
        }
    }

    private fun confirmDelete(entry: HostEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.site_data_delete_title)
            .setMessage(getString(R.string.site_data_delete_message, entry.host))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.site_data_delete_confirm) { _, _ ->
                lifecycleScope.launch {
                    cleaner.clearSite(entry.host)
                    render()
                }
            }
            .show()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SiteDataActivity::class.java)
    }
}

private class SitesAdapter(
    private val icons: BrowserIcons,
    private val scope: CoroutineScope,
    private val onDelete: (HostEntry) -> Unit,
) : RecyclerView.Adapter<SitesAdapter.Holder>() {

    private var items: List<HostEntry> = emptyList()

    fun submit(entries: List<HostEntry>) {
        items = entries
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemSiteRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemSiteRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: HostEntry) = with(binding) {
            val context = root.context
            siteRowHost.text = entry.host
            siteRowDetail.text = context.getString(
                R.string.site_data_detail,
                context.resources.getQuantityString(
                    R.plurals.site_pages_count,
                    entry.pages,
                    entry.pages,
                ),
                Instant.ofEpochMilli(entry.lastVisit)
                    .atZone(ZoneId.systemDefault())
                    .format(DAY_FORMAT),
            )
            siteRowIcon.bindSite("https://${entry.host}/", icons, scope)
            root.setOnClickListener { onDelete(entry) }
            btnDeleteSite.setOnClickListener { onDelete(entry) }
        }
    }

    private companion object {
        val DAY_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault())
    }
}
