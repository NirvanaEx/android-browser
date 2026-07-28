package com.upgrid.browser.account

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ActivityLoginBinding
import com.upgrid.browser.prefs.BrowserPreferences
import kotlinx.coroutines.launch

/**
 * Sign in to the browser's own account.
 *
 * What the user sees is two fields. What happens behind them: the credentials
 * go to the account server, the profile comes back, the VPN is written and
 * armed. When the server can't be reached the same password still signs in
 * against the device, so the browser is never locked out by a network.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val components get() = (application as BrowserApplication).components
    private val controller by lazy { AccountController(components) }
    private val prefs by lazy { BrowserPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.pageTitle.setText(R.string.account_title)
        binding.header.btnBack.setOnClickListener { finish() }

        binding.accountServer.setText(prefs.accountServer)
        binding.accountLogin.setText(
            controller.current?.login ?: AccountStore.DEFAULT_LOGIN,
        )

        binding.btnSignIn.setOnClickListener { submit() }
        binding.accountPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        renderSignedIn()
    }

    override fun onPause() {
        super.onPause()
        prefs.accountServer = binding.accountServer.text?.toString().orEmpty()
    }

    private fun renderSignedIn() {
        val account = controller.current ?: return
        binding.loginStatus.text = getString(R.string.account_signed_in_as, account.name)
        binding.btnSignIn.setText(R.string.account_sign_in_again)
    }

    private fun submit() {
        val login = binding.accountLogin.text?.toString()?.trim().orEmpty()
        val password = binding.accountPassword.text?.toString().orEmpty()
        if (login.isEmpty() || password.isEmpty()) {
            binding.loginStatus.setText(R.string.account_needs_both)
            return
        }
        // Saved before the request: the server address is part of the attempt.
        prefs.accountServer = binding.accountServer.text?.toString().orEmpty()

        binding.btnSignIn.isEnabled = false
        binding.loginStatus.setText(R.string.account_signing_in)

        lifecycleScope.launch {
            val result = controller.signIn(login, password)
            binding.btnSignIn.isEnabled = true

            when (result) {
                // Signing in closes the screen, always. It used to stay open
                // with "server unreachable" written under the button when the
                // device carried the sign-in — which is a true sentence about
                // our plumbing and, to the person reading it, indistinguishable
                // from having been refused. Where the profile is or isn't
                // belongs on the VPN screen, which says so in its own words.
                is AccountController.Result.Success -> {
                    val message = if (result.provisioned) {
                        getString(R.string.account_welcome, result.account.name)
                    } else {
                        getString(R.string.account_signed_in_no_profile, result.account.name)
                    }
                    Toast.makeText(this@LoginActivity, message, Toast.LENGTH_SHORT).show()
                    finish()
                }
                AccountController.Result.WrongCredentials ->
                    binding.loginStatus.setText(R.string.account_wrong)
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, LoginActivity::class.java)
    }
}
