package com.upgrid.browser.vpn

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.upgrid.browser.account.LoginActivity
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ActivityVpnBinding
import com.wireguard.android.backend.Tunnel
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.launch

/**
 * VPN setup and the connect switch.
 *
 * The profile is a form rather than a text box: a `wg-quick` file is nine
 * values and editing one of them inside a wall of text on a phone keyboard is
 * miserable. Pasting a whole config still works — that's the fast path — and it
 * fills the same fields.
 *
 * The consent dialog belongs to an Activity, which is why connecting lives here
 * and in MainActivity rather than in [VpnController]: `VpnService.prepare`
 * returns an Intent the user has to accept, once per install.
 */
class VpnActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVpnBinding
    private val components get() = (application as BrowserApplication).components
    private val controller get() = components.vpn
    private val settings by lazy { VpnSettings(this) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                connect()
            } else {
                toast(getString(R.string.vpn_permission_denied))
            }
        }

    /** Only for the status notification. Whatever the answer, the tunnel works. */
    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Pick a `wg-quick` file and load it.
     *
     * The picker is opened with a wildcard filter rather than a mime type:
     * `.conf` has no registered one, so Android reports it as
     * `application/octet-stream` on one device and `text/plain` on the next,
     * and filtering on either hides the very file the user came to pick.
     */
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val text = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                toast(getString(R.string.vpn_import_failed))
            } else {
                applyConfig(text)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVpnBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.pageTitle.setText(R.string.vpn_title)
        binding.header.btnBack.setOnClickListener { finish() }

        load()
        wireButtons()
        renderProvision()

        // Nothing configured means the only useful things on this screen are
        // behind the "advanced" fold — paste a config, generate a key. Opening
        // it for them beats a collapsed screen with one dead button on it.
        setAdvancedOpen(!settings.isConfigured)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.refresh()
                controller.tunnelState.collect { renderState(it) }
            }
        }
    }

    /** Fields are the source of truth while the screen is open; save on the way out. */
    override fun onPause() {
        super.onPause()
        save()
    }

    /** Signing in elsewhere can fill the whole form in while we're away. */
    override fun onResume() {
        super.onResume()
        load()
        renderProvision()
        // Signing in can have filled the profile in while we were away, and the
        // card renders off the settings as well as off the tunnel state.
        renderState(controller.tunnelState.value)
    }

    /**
     * Say where the profile came from.
     *
     * The difference matters: a profile that came with the account will come
     * back by itself on a new phone, and one typed in by hand will not.
     */
    private fun renderProvision() {
        val account = components.accounts.current
        val provisioned = account != null && components.accounts.vpnConfig != null
        binding.vpnProvision.text = when {
            provisioned -> getString(R.string.vpn_provisioned_by, account!!.name)
            account != null -> getString(R.string.vpn_provision_none)
            else -> getString(R.string.vpn_provision_sign_in)
        }
        binding.btnVpnSignIn.isVisible = !provisioned
    }

    // --- Form --------------------------------------------------------------

    private fun load() = with(binding) {
        vpnPrivateKey.setText(settings.privateKey)
        vpnAddress.setText(settings.address)
        vpnDns.setText(settings.dns)
        vpnMtu.setText(settings.mtu)
        vpnEndpoint.setText(settings.endpoint)
        vpnPeerKey.setText(settings.peerPublicKey)
        vpnPreshared.setText(settings.presharedKey)
        vpnAllowed.setText(settings.allowedIps)
        vpnKeepalive.setText(settings.keepalive)
        vpnAutoConnect.isChecked = settings.autoConnect
        renderPublicKey()
    }

    private fun save() = with(binding) {
        settings.privateKey = vpnPrivateKey.text?.toString().orEmpty()
        settings.address = vpnAddress.text?.toString().orEmpty()
        settings.dns = vpnDns.text?.toString().orEmpty()
        settings.mtu = vpnMtu.text?.toString().orEmpty()
        settings.endpoint = vpnEndpoint.text?.toString().orEmpty()
        settings.peerPublicKey = vpnPeerKey.text?.toString().orEmpty()
        settings.presharedKey = vpnPreshared.text?.toString().orEmpty()
        settings.allowedIps = vpnAllowed.text?.toString().orEmpty()
        settings.keepalive = vpnKeepalive.text?.toString().orEmpty()
        settings.autoConnect = vpnAutoConnect.isChecked
    }

    /**
     * The public half, derived from whatever private key is in the field.
     *
     * Shown because it is the one value the *server* needs: adding this device
     * as a peer means pasting this string into the server's config, and there
     * is nowhere else to read it from.
     */
    private fun renderPublicKey() {
        val private = binding.vpnPrivateKey.text?.toString()?.trim().orEmpty()
        val public = runCatching {
            KeyPair(Key.fromBase64(private)).publicKey.toBase64()
        }.getOrNull()
        binding.vpnPublicKey.setText(public ?: getString(R.string.vpn_public_key_none))
    }

    private fun wireButtons() = with(binding) {
        btnVpnToggle.setOnClickListener { toggle() }

        btnVpnSignIn.setOnClickListener {
            startActivity(LoginActivity.intent(this@VpnActivity))
        }

        // Everything below the divider is the part nobody should have to open.
        btnVpnAdvanced.setOnClickListener { setAdvancedOpen(!vpnAdvanced.isVisible) }

        vpnAutoConnect.setOnCheckedChangeListener { _, checked ->
            settings.autoConnect = checked
        }

        btnVpnPaste.setOnClickListener { pasteConfig() }

        btnVpnImport.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }

        btnVpnGenerate.setOnClickListener {
            val pair = KeyPair()
            vpnPrivateKey.setText(pair.privateKey.toBase64())
            renderPublicKey()
            toast(getString(R.string.vpn_generated))
        }

        vpnPublicField.setEndIconOnClickListener {
            copy(vpnPublicKey.text?.toString().orEmpty())
        }

        btnVpnSave.setOnClickListener {
            save()
            renderPublicKey()
            renderState(controller.tunnelState.value)
            toast(getString(R.string.vpn_saved))
        }

        btnVpnForget.setOnClickListener { confirmForget() }
    }

    /** Paste a whole `wg-quick` config from the clipboard. */
    private fun pasteConfig() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            toast(getString(R.string.vpn_paste_empty))
            return
        }
        applyConfig(text)
    }

    /**
     * Load a `wg-quick` config, from wherever it came from.
     *
     * Saves the form first: [VpnSettings.importFrom] writes into the same
     * preferences the fields were loaded from, and re-reading them afterwards
     * would otherwise throw away anything typed but not yet saved.
     */
    private fun applyConfig(text: String) {
        save()
        if (!settings.importFrom(text)) {
            toast(getString(R.string.vpn_paste_bad))
            return
        }
        load()
        setAdvancedOpen(false)
        renderState(controller.tunnelState.value)
        toast(getString(R.string.vpn_paste_ok))
    }

    private fun copy(value: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), value))
        toast(getString(R.string.vpn_copied))
    }

    private fun confirmForget() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vpn_forget_title)
            .setMessage(R.string.vpn_forget_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.vpn_forget_confirm) { _, _ ->
                lifecycleScope.launch {
                    controller.disconnect()
                    settings.clear()
                    load()
                }
            }
            .show()
    }

    // --- Connect -----------------------------------------------------------

    private fun setAdvancedOpen(open: Boolean) {
        binding.vpnAdvanced.isVisible = open
        binding.btnVpnAdvanced.setIconResource(
            if (open) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
        )
    }

    private fun toggle() {
        save()
        if (controller.isUp) {
            lifecycleScope.launch { controller.disconnect() }
            return
        }
        if (!settings.isConfigured) {
            // Say what's missing and put it in front of them, rather than
            // toasting at a screen that shows no way to fix it.
            toast(getString(R.string.vpn_incomplete))
            setAdvancedOpen(true)
            return
        }
        // Returns an Intent the first time, and null once the user has agreed.
        val consent = VpnService.prepare(this)
        if (consent != null) permissionLauncher.launch(consent) else connect()
    }

    private fun connect() {
        // Ask now, not on first launch: this is the moment there is something
        // to post. The tunnel comes up either way.
        if (VpnNotifications.needsPermission(this)) {
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        binding.btnVpnToggle.isEnabled = false
        lifecycleScope.launch {
            val result = controller.connect(settings)
            binding.btnVpnToggle.isEnabled = true
            result.onFailure { error ->
                toast(getString(R.string.vpn_failed, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    // --- Render ------------------------------------------------------------

    private fun renderState(state: Tunnel.State) {
        val up = state == Tunnel.State.UP
        // "Disconnected" and "not set up" are different answers to the same
        // question and only one of them means "press the button".
        binding.vpnState.setText(
            when {
                up -> R.string.vpn_state_on
                settings.isConfigured -> R.string.vpn_state_off
                else -> R.string.vpn_not_configured
            },
        )
        binding.vpnStateIcon.setImageResource(
            if (up) R.drawable.ic_shield else R.drawable.ic_shield_off,
        )
        binding.vpnStateIcon.setColorFilter(
            MaterialColors.getColor(
                binding.vpnStateIcon,
                if (up) androidx.appcompat.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurfaceVariant,
            ),
        )
        binding.btnVpnToggle.setText(if (up) R.string.vpn_disconnect else R.string.vpn_connect)
        binding.btnVpnToggle.setIconResource(
            if (up) R.drawable.ic_shield_off else R.drawable.ic_shield,
        )

        if (up) {
            binding.vpnDetail.isVisible = true
            lifecycleScope.launch {
                val transfer = controller.transfer()
                binding.vpnDetail.text = if (transfer == null) {
                    settings.endpoint
                } else {
                    getString(
                        R.string.vpn_transfer,
                        android.text.format.Formatter.formatShortFileSize(this@VpnActivity, transfer.first),
                        android.text.format.Formatter.formatShortFileSize(this@VpnActivity, transfer.second),
                    )
                }
            }
        } else {
            // The endpoint is the one line worth reading when it's down; with
            // nothing set up there is no second line to write, and the card
            // saying "not set up" twice reads as an error.
            binding.vpnDetail.text = settings.endpoint
            binding.vpnDetail.isVisible = settings.endpoint.isNotBlank()
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        fun intent(context: Context): Intent = Intent(context, VpnActivity::class.java)
    }
}
