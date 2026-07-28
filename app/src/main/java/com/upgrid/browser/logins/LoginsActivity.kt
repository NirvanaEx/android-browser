package com.upgrid.browser.logins

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ActivityLoginsBinding
import com.upgrid.browser.databinding.DialogLoginBinding
import com.upgrid.browser.databinding.ItemLoginRowBinding
import kotlinx.coroutines.CoroutineScope
import mozilla.components.browser.icons.BrowserIcons

/**
 * Saved passwords: the list, and the one dialog that shows a password.
 *
 * The list never renders a password. Everything that does — revealing it,
 * copying it — is behind a tap, so the screen can be opened in front of someone
 * without handing them the contents.
 */
class LoginsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginsBinding
    private val components get() = (application as BrowserApplication).components
    private val store get() = components.logins
    private lateinit var adapter: LoginsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.pageTitle.setText(R.string.logins_title)
        binding.header.btnBack.setOnClickListener { finish() }
        binding.header.btnHeaderAction.setText(R.string.logins_clear)
        binding.header.btnHeaderAction.setOnClickListener { confirmClearAll() }

        adapter = LoginsAdapter(
            icons = components.icons,
            scope = lifecycleScope,
            onOpen = ::showDetails,
            onDelete = ::confirmDelete,
        )
        binding.loginsList.layoutManager = LinearLayoutManager(this)
        binding.loginsList.adapter = adapter

        render()
    }

    private fun render() {
        val logins = store.all()
        adapter.submit(logins)
        binding.loginsList.isVisible = logins.isNotEmpty()
        binding.emptyState.isVisible = logins.isEmpty()
        binding.header.btnHeaderAction.isVisible = logins.isNotEmpty()
    }

    private fun showDetails(login: Login) {
        val body = DialogLoginBinding.inflate(layoutInflater)
        body.loginUserValue.setText(login.username)
        body.loginPassValue.setText(login.password)
        body.loginUserField.setEndIconOnClickListener {
            copy(login.username, R.string.logins_copied_user)
        }
        body.btnCopyPassword.setOnClickListener {
            copy(login.password, R.string.logins_copied_password)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(login.host)
            .setIcon(R.drawable.ic_key)
            .setView(body.root)
            .setNegativeButton(R.string.logins_delete) { _, _ -> confirmDelete(login) }
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    /**
     * The clipboard, marked sensitive so Android 13+ doesn't show the value in
     * its copy confirmation — the one place a password would otherwise appear
     * on screen without being asked for.
     */
    private fun copy(value: String, messageRes: Int) {
        val clip = ClipData.newPlainText(getString(R.string.app_name), value).apply {
            description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(login: Login) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logins_delete_title)
            .setMessage(getString(R.string.logins_delete_message, login.host))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.logins_delete) { _, _ ->
                store.delete(login.id)
                render()
            }
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logins_clear_title)
            .setMessage(R.string.logins_clear_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.logins_clear_confirm) { _, _ ->
                store.clear()
                render()
            }
            .show()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, LoginsActivity::class.java)
    }
}

private class LoginsAdapter(
    private val icons: BrowserIcons,
    private val scope: CoroutineScope,
    private val onOpen: (Login) -> Unit,
    private val onDelete: (Login) -> Unit,
) : RecyclerView.Adapter<LoginsAdapter.Holder>() {

    private var items: List<Login> = emptyList()

    fun submit(logins: List<Login>) {
        items = logins
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemLoginRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemLoginRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(login: Login) = with(binding) {
            loginHost.text = login.host
            loginUser.text = login.username.ifBlank {
                root.context.getString(R.string.logins_no_username)
            }
            loginIcon.bindSite("https://${login.host}/", icons, scope)
            root.setOnClickListener { onOpen(login) }
            btnDeleteLogin.setOnClickListener { onDelete(login) }
        }
    }
}
