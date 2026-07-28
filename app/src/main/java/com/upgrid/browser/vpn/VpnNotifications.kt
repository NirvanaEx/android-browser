package com.upgrid.browser.vpn

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A notification for as long as the tunnel is up.
 *
 * Android puts its own key in the status bar when any VPN is running, but that
 * key says only "some app has a VPN" — it doesn't say which app, whether it is
 * still connected, or offer a way to stop it. This one does all three, and the
 * "Disconnect" action is the shortest path there is: no launching the browser,
 * no finding the menu.
 *
 * Ongoing and low importance: it is a status, not an event, so it makes no
 * sound and cannot be swiped away while the tunnel is up.
 */
class VpnNotifications(private val context: Context) {

    fun render(up: Boolean) {
        if (up) show() else hide()
    }

    private fun show() {
        // Denied notifications are a normal state, not an error: the tunnel
        // works either way and there is nothing to report to the user about a
        // notification they refused.
        if (!allowed()) return
        ensureChannel()

        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            VpnActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            context,
            REQUEST_STOP,
            Intent(context, VpnActionReceiver::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val endpoint = VpnSettings(context).endpoint
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.vpn_notification_title))
            .setContentText(
                if (endpoint.isBlank()) {
                    context.getString(R.string.vpn_state_on)
                } else {
                    context.getString(R.string.vpn_notification_text, endpoint)
                },
            )
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(
                R.drawable.ic_shield_off,
                context.getString(R.string.vpn_disconnect),
                stop,
            )
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
    }

    private fun hide() {
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.vpn_notification_channel_hint)
                setShowBadge(false)
            },
        )
    }

    private fun allowed(): Boolean =
        !needsPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    companion object {
        private const val CHANNEL = "upgrid_vpn"
        private const val ID = 4201
        private const val REQUEST_OPEN = 0
        private const val REQUEST_STOP = 1

        const val ACTION_DISCONNECT = "com.upgrid.browser.action.VPN_DISCONNECT"

        /**
         * True when the runtime notification permission is still missing.
         *
         * Asked for at the moment the user connects rather than on first launch:
         * a browser that opens with a permission dialog about notifications it
         * has not yet had any reason to post is asking the wrong question.
         */
        fun needsPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        }
    }
}

/**
 * The notification's "Disconnect" button.
 *
 * `goAsync` because bringing the tunnel down is blocking work and a receiver
 * that returns first would be killed mid-call.
 */
class VpnActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VpnNotifications.ACTION_DISCONNECT) return
        val app = context.applicationContext as? BrowserApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                app.components.vpn.disconnect()
            } finally {
                pending.finish()
            }
        }
    }
}
