package com.upgrid.browser.vpn

import android.content.Context
import android.text.format.Formatter
import com.upgrid.browser.R

/**
 * How the tunnel's numbers are written, in one place.
 *
 * Three screens show the same speed — the notification, the app menu's VPN row
 * and the VPN screen itself — and a user glancing between them should not have
 * to work out whether "1.2 MB" and "1,2 МБ/с" are the same measurement. The
 * byte sizes go through the platform formatter so the units and the decimal
 * separator follow the phone's locale rather than ours.
 */
object VpnFormat {

    /** "↓ 1.2 MB/s   ↑ 240 kB/s" */
    fun rate(context: Context, status: VpnStatus.Snapshot): String = context.getString(
        R.string.vpn_notification_rate,
        perSecond(context, status.downstream),
        perSecond(context, status.upstream),
    )

    /** "Received 1.2 GB · sent 88 MB" */
    fun total(context: Context, status: VpnStatus.Snapshot): String = context.getString(
        R.string.vpn_transfer,
        size(context, status.received),
        size(context, status.sent),
    )

    fun perSecond(context: Context, bytes: Long): String =
        context.getString(R.string.vpn_speed_per_second, size(context, bytes))

    fun size(context: Context, bytes: Long): String =
        Formatter.formatShortFileSize(context, bytes)
}
