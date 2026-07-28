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
class VpnStatus(private val controller: VpnController) {

    /**
     * @property up whether the tunnel is carrying traffic at all.
     * @property received total bytes in since the tunnel came up.
     * @property sent total bytes out since the tunnel came up.
     * @property downstream bytes per second in, over the last sample.
     * @property upstream bytes per second out, over the last sample.
     * @property sampled false until two samples have been taken, i.e. until
     * there is a rate to show rather than a placeholder zero.
     */
    data class Snapshot(
        val up: Boolean = false,
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

        while (true) {
            val transfer = controller.transfer()
            // elapsedRealtime, not wall clock: a rate divided by a wall-clock
            // delta goes wrong the one time the phone's clock is corrected.
            val now = SystemClock.elapsedRealtime()

            if (transfer == null) {
                // The backend can refuse mid-handshake. The tunnel is still up;
                // we just have nothing new to say about it.
                current.value = current.value.copy(up = true)
            } else {
                val (rx, tx) = transfer
                val elapsed = now - previousAt
                val first = previousAt == 0L
                current.value = Snapshot(
                    up = true,
                    received = rx,
                    sent = tx,
                    // Counters restart with the tunnel, so a reconnect can make
                    // a delta negative. That's zero traffic, not negative
                    // traffic.
                    downstream = if (first) 0 else rate(rx - previousRx, elapsed),
                    upstream = if (first) 0 else rate(tx - previousTx, elapsed),
                    sampled = !first,
                )
                previousRx = rx
                previousTx = tx
                previousAt = now
            }

            delay(SAMPLE_MS)
        }
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
    }
}
