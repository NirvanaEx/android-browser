package com.upgrid.browser.vpn

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * The WireGuard tunnel.
 *
 * A thin wrapper over the official `com.wireguard.android:tunnel` backend,
 * which is the same userspace implementation the WireGuard app ships: the
 * cryptography and the TUN loop are not things to reimplement.
 *
 * One instance per process — the tunnel outlives every screen, and a second
 * backend would fight the first over the same VpnService.
 *
 * The state is a flow rather than a getter because it changes without anyone
 * asking: the system can revoke a VPN when another app starts one, and the
 * backend reports that through [Tunnel.onStateChange].
 */
class VpnController(private val context: Context) {

    private val state = MutableStateFlow(Tunnel.State.DOWN)

    /** Live tunnel state. Collected by the settings screen and the app menu. */
    val tunnelState: StateFlow<Tunnel.State> = state.asStateFlow()

    val isUp: Boolean get() = state.value == Tunnel.State.UP

    /**
     * Created lazily and never on the main thread's critical path: the
     * constructor loads the wireguard-go shared library, which is the one
     * expensive thing here.
     */
    private val backend: Backend by lazy { GoBackend(context.applicationContext) }

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            state.value = newState
        }
    }

    /**
     * Bring the tunnel up.
     *
     * Blocking work — DNS resolution of the endpoint with retries, then the
     * VpnService handshake — so it runs on IO and the caller awaits it.
     *
     * The VPN consent dialog is NOT handled here: `VpnService.prepare` needs an
     * Activity to launch its intent from, and this object has none. Callers
     * must have done that first; see MainActivity.toggleVpn.
     */
    suspend fun connect(settings: VpnSettings): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val config = Config.parse(
                ByteArrayInputStream(settings.toWgQuick().toByteArray(Charsets.UTF_8)),
            )
            backend.setState(tunnel, Tunnel.State.UP, config)
            state.value = backend.getState(tunnel)
        }.onFailure { Log.w(TAG, "Bringing the tunnel up failed", it) }
    }

    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
            state.value = backend.getState(tunnel)
        }.onFailure { Log.w(TAG, "Bringing the tunnel down failed", it) }
    }

    /**
     * Re-read the backend's own idea of the state.
     *
     * Needed on resume: the tunnel can be torn down from the system's VPN
     * notification, which the backend learns about but our flow does not until
     * something asks.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        runCatching { state.value = backend.getState(tunnel) }
        Unit
    }

    /** Bytes in / out, or null when the tunnel is down or the backend refuses. */
    suspend fun transfer(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            val stats = backend.getStatistics(tunnel)
            stats.totalRx() to stats.totalTx()
        }.getOrNull()
    }

    private companion object {
        const val TAG = "VpnController"

        /** Tunnel.NAME_PATTERN: letters, digits and a few symbols, max 15. */
        const val TUNNEL_NAME = "upgrid"
    }
}
