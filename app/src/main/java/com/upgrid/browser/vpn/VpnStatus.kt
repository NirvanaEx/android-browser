package com.upgrid.browser.vpn

import android.os.SystemClock
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * How the tunnel is doing, right now.
 *
 * [VpnController] answers "is it up" and "how many bytes in total". Neither is
 * what anybody actually wants to know while a VPN is running: the question is
 * *is traffic moving, and how fast*. A total that only grows says nothing about
 * whether the connection is alive this second — a stalled tunnel and an idle
 * one look identical from the counters alone.
 *
 * So this samples the counters and turns them into a rate. One instance per
 * process, started from [com.upgrid.browser.BrowserApplication], because both
 * the notification and the VPN screen read it and neither owns the tunnel.
 *
 * Sampling stops the moment the tunnel goes down — the loop lives inside a
 * `collectLatest` on the tunnel state, so a disconnect cancels it rather than
 * leaving a timer polling a backend that has nothing to say.
 */
class VpnStatus(
    private val controller: VpnController,
    private val settings: VpnSettings,
) {

    /**
     * Whether the tunnel is talking to the server, as opposed to merely
     * existing. See [health] for why the difference is the whole point.
     */
    enum class Health {
        /** Up, but no handshake yet. Normal for the first few seconds. */
        CONNECTING,

        /** Up and the server answered recently. */
        ONLINE,

        /** Up, and the server has gone quiet. Nothing will load. */
        STALLED,
    }

    /**
     * @property up whether the tunnel interface exists.
     * @property health whether it is reaching the server. Only meaningful
     * while [up]; a down tunnel reports [Health.CONNECTING] and nobody reads it.
     * @property received total bytes in since the tunnel came up.
     * @property sent total bytes out since the tunnel came up.
     * @property downstream bytes per second in, over the last sample.
     * @property upstream bytes per second out, over the last sample.
     * @property sampled false until two samples have been taken, i.e. until
     * there is a rate to show rather than a placeholder zero.
     */
    data class Snapshot(
        val up: Boolean = false,
        val health: Health = Health.CONNECTING,
        val received: Long = 0,
        val sent: Long = 0,
        val downstream: Long = 0,
        val upstream: Long = 0,
        val sampled: Boolean = false,
    ) {
        companion object {
            val DOWN = Snapshot()
        }
    }

    private val current = MutableStateFlow(Snapshot.DOWN)

    /** Live readout. Down-state whenever the tunnel isn't up. */
    val state: StateFlow<Snapshot> = current.asStateFlow()

    /**
     * Begin sampling. Idempotent per scope; call once, from the Application.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            controller.tunnelState.collectLatest { tunnel ->
                if (tunnel != Tunnel.State.UP) {
                    current.value = Snapshot.DOWN
                    return@collectLatest
                }
                sample()
            }
        }
    }

    private suspend fun sample() {
        var previousRx = 0L
        var previousTx = 0L
        var previousAt = 0L
        val startedAt = SystemClock.elapsedRealtime()

        while (true) {
            val stats = controller.stats()
            // elapsedRealtime, not wall clock: a rate divided by a wall-clock
            // delta goes wrong the one time the phone's clock is corrected.
            val now = SystemClock.elapsedRealtime()

            if (stats == null) {
                // The backend can refuse mid-handshake. The tunnel is still up;
                // we just have nothing new to say about it.
                current.value = current.value.copy(up = true)
            } else {
                val elapsed = now - previousAt
                val first = previousAt == 0L
                current.value = Snapshot(
                    up = true,
                    health = health(stats.handshakeAt, now - startedAt),
                    received = stats.received,
                    sent = stats.sent,
                    // Counters restart with the tunnel, so a reconnect can make
                    // a delta negative. That's zero traffic, not negative
                    // traffic.
                    downstream = if (first) 0 else rate(stats.received - previousRx, elapsed),
                    upstream = if (first) 0 else rate(stats.sent - previousTx, elapsed),
                    sampled = !first,
                )
                previousRx = stats.received
                previousTx = stats.sent
                previousAt = now
            }

            delay(SAMPLE_MS)
        }
    }

    /**
     * Is anybody there?
     *
     * `Backend.getState` answers "does the tunnel interface exist", which goes
     * true the moment the interface is built and stays true while the server is
     * unreachable — so a shield wired to it alone turns green over a tunnel
     * that has never carried a single packet, which is the one failure a VPN
     * screen exists to report. The handshake is the honest signal: WireGuard
     * renews it two minutes into its life for as long as there is anything to
     * send, and `PersistentKeepalive` is what guarantees there is.
     *
     * Wall clock here, unavoidably — the handshake is reported in epoch
     * milliseconds, so that is the clock it has to be compared against. A
     * correction to the phone's clock can therefore age a handshake wrongly for
     * one sample. Forwards it recovers by itself two seconds later; backwards
     * it reads as negative, which is treated as fresh rather than stale,
     * because a handshake we were told about did happen.
     */
    private fun health(handshakeAt: Long, upFor: Long): Health {
        if (handshakeAt <= 0L) {
            // None yet. The first exchange takes a moment on a mobile network;
            // past that, silence means the server is not answering at all.
            return if (upFor < GRACE_MS) Health.CONNECTING else Health.STALLED
        }
        val age = System.currentTimeMillis() - handshakeAt
        return if (age <= staleAfterMs()) Health.ONLINE else Health.STALLED
    }

    /**
     * How old a handshake may get before the tunnel counts as stalled.
     *
     * Built from the keepalive rather than fixed: WireGuard renews a handshake
     * 120 s in, but only when it has something to send, and on an idle tunnel
     * `PersistentKeepalive` is the only thing that keeps it having something.
     * Six keepalives of slack, so a couple lost to a bad mobile connection
     * don't get called an outage. Without a keepalive an idle tunnel is
     * *supposed* to go quiet, so the window widens rather than the state
     * turning red on a connection that is merely unused.
     */
    private fun staleAfterMs(): Long {
        val keepalive = settings.keepalive.trim().toLongOrNull() ?: 0L
        if (keepalive <= 0L) return SILENT_STALE_MS
        return (keepalive * 6_000L).coerceIn(STALE_MS, SILENT_STALE_MS)
    }

    private fun rate(delta: Long, elapsedMs: Long): Long =
        if (elapsedMs <= 0) 0 else (delta * 1000 / elapsedMs).coerceAtLeast(0)

    private companion object {
        /**
         * Two seconds. One reads as a speedometer and doubles the wakeups for
         * a number nobody watches that closely; five is long enough that a
         * short burst is over before it shows up at all.
         */
        const val SAMPLE_MS = 2_000L

        /** Room for a first handshake over a slow mobile connection. */
        const val GRACE_MS = 12_000L

        /** Floor for the stale window: WireGuard's own 120 s renewal, plus slack. */
        const val STALE_MS = 150_000L

        /** The window when no keepalive is set and silence is allowed to mean nothing. */
        const val SILENT_STALE_MS = 300_000L
    }
}
