package com.upgrid.browser.download

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ActivityDownloadsBinding
import com.upgrid.browser.databinding.ItemDownloadRowBinding
import com.upgrid.browser.ui.HostTile
import kotlinx.coroutines.launch
import java.io.File

/**
 * The downloads list.
 *
 * Rows are live: a file still being written shows its progress bar move,
 * because [DownloadRecords] publishes a flow and the manager writes into it
 * from whichever thread is doing the copying.
 *
 * Tapping a finished row opens the file with whatever app handles its type.
 * Two ways to hand it over, matching the two places the bytes can be: a
 * MediaStore `content://` URI on Android 10+, and a FileProvider URI for the
 * app-private directory used below that.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private val components get() = (application as BrowserApplication).components
    private val records get() = components.downloads.records
    private lateinit var adapter: DownloadsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.pageTitle.setText(R.string.downloads_title)
        binding.header.btnBack.setOnClickListener { finish() }
        binding.header.btnHeaderAction.setText(R.string.downloads_clear)
        binding.header.btnHeaderAction.setOnClickListener { confirmClear() }

        adapter = DownloadsAdapter(
            onOpen = ::open,
            onDelete = ::confirmDelete,
        )
        binding.downloadsList.layoutManager = LinearLayoutManager(this)
        binding.downloadsList.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                records.records.collect { list ->
                    adapter.submit(list)
                    binding.downloadsList.isVisible = list.isNotEmpty()
                    binding.emptyState.isVisible = list.isEmpty()
                    binding.header.btnHeaderAction.isVisible = list.isNotEmpty()
                }
            }
        }
    }

    /** Hand the file to whatever app claims its type. */
    private fun open(record: DownloadRecord) {
        if (!record.isFinished) return
        val uri = uriFor(record)
        if (uri == null) {
            Toast.makeText(this, R.string.downloads_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, record.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.downloads_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun uriFor(record: DownloadRecord): Uri? {
        if (record.uri.isNotBlank()) return Uri.parse(record.uri)
        if (record.path.isBlank()) return null
        val file = File(record.path)
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(this, "$packageName.files", file)
        }.getOrNull()
    }

    /**
     * Deleting removes the file, not just the row — a downloads list where
     * "delete" leaves the file on the phone is a list that lies about what it
     * is showing.
     */
    private fun confirmDelete(record: DownloadRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.downloads_delete_title)
            .setMessage(getString(R.string.downloads_delete_message, record.fileName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.downloads_delete_confirm) { _, _ ->
                deleteFile(record)
                records.remove(record.id)
            }
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.downloads_clear_title)
            .setMessage(R.string.downloads_clear_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.downloads_clear_confirm) { _, _ -> records.clear() }
            .show()
    }

    /** Best effort: the user may have moved or deleted the file themselves. */
    private fun deleteFile(record: DownloadRecord) {
        runCatching {
            when {
                record.uri.isNotBlank() ->
                    contentResolver.delete(Uri.parse(record.uri), null, null)
                record.path.isNotBlank() -> File(record.path).delete()
                else -> Unit
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, DownloadsActivity::class.java)
    }
}

private class DownloadsAdapter(
    private val onOpen: (DownloadRecord) -> Unit,
    private val onDelete: (DownloadRecord) -> Unit,
) : RecyclerView.Adapter<DownloadsAdapter.Holder>() {

    private var items: List<DownloadRecord> = emptyList()

    fun submit(records: List<DownloadRecord>) {
        items = records
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemDownloadRowBinding.inflate(
            android.view.LayoutInflater.from(parent.context),
            parent,
            false,
        ),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemDownloadRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(record: DownloadRecord) = with(binding) {
            val context = root.context
            downloadName.text = record.fileName
            downloadIcon.bindGlyph(
                when (record.status) {
                    DownloadRecord.Status.FAILED -> R.drawable.ic_close
                    else -> R.drawable.ic_download
                },
            )

            val size = Formatter.formatShortFileSize(context, record.bytes.coerceAtLeast(0))
            val host = HostTile.hostOf(record.url)
            downloadStatus.text = when (record.status) {
                DownloadRecord.Status.RUNNING -> {
                    val total = record.totalBytes
                    if (total > 0) {
                        context.getString(
                            R.string.downloads_progress,
                            size,
                            Formatter.formatShortFileSize(context, total),
                        )
                    } else {
                        context.getString(R.string.downloads_running, size)
                    }
                }
                DownloadRecord.Status.DONE ->
                    listOf(size, host).filter { it.isNotBlank() }.joinToString(" · ")
                DownloadRecord.Status.FAILED -> context.getString(R.string.downloads_failed)
            }

            // Mode first, visibility second: Material's progress indicator
            // throws if it is asked to go indeterminate while on screen.
            val fraction = record.progress
            downloadProgress.isVisible = false
            if (fraction == null) {
                downloadProgress.isIndeterminate = true
            } else {
                downloadProgress.isIndeterminate = false
                downloadProgress.setProgressCompat((fraction * 100).toInt(), true)
            }
            downloadProgress.isVisible = record.status == DownloadRecord.Status.RUNNING

            root.setOnClickListener { onOpen(record) }
            btnDeleteDownload.setOnClickListener { onDelete(record) }
        }
    }
}
