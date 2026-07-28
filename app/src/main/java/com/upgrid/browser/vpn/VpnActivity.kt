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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVpnBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.pageTitle.setText(R.string.vpn_title)
        binding.header.btnBack.setOnClickListener { finish() }

        load()
        wireButtons()
        renderProvision()

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
        btnVpnAdvanced.setOnClickListener {
            val open = !vpnAdvanced.isVisible
            vpnAdvanced.isVisible = open
            btnVpnAdvanced.setIconResource(
                if (open) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
            )
        }

        vpnAutoConnect.setOnCheckedChangeListener { _, checked ->
            settings.autoConnect = checked
        }

        btnVpnPaste.setOnClickListener { pasteConfig() }

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
            toast(getString(R.string.vpn_saved))
        }

        btnVpnForget.setOnClickListener { confirmForget() }
    }

    /**
     * Paste a whole `wg-quick` config from the clipboard.
     *
     * Saves the form first: [VpnSettings.importFrom] writes into the same
     * preferences the fields were loaded from, and re-reading them afterwards
     * would otherwise throw away anything typed but not yet saved.
     */
    private fun pasteConfig() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            toast(getString(R.string.vpn_paste_empty))
            return
        }
        save()
        if (!settings.importFrom(text)) {
            toast(getString(R.string.vpn_paste_bad))
            return
        }
        load()
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

    private fun toggle() {
        save()
        if (controller.isUp) {
            lifecycleScope.launch { controller.disconnect() }
            return
        }
        if (!settings.isConfigured) {
            toast(getString(R.string.vpn_incomplete))
            return
        }
        // Returns an Intent the first time, and null once the user has agreed.
        val consent = VpnService.prepare(this)
        if (consent != null) permissionLauncher.launch(consent) else connect()
    }

    private fun connect() {
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
        binding.vpnState.setText(if (up) R.string.vpn_state_on else R.string.vpn_state_off)
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
            binding.vpnDetail.text = settings.endpoint.ifBlank {
                getString(R.string.vpn_not_configured)
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        fun intent(context: Context): Intent = Intent(context, VpnActivity::class.java)
    }
}
