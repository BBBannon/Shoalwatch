package app.shoalwatch.data.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.shoalwatch.R
import app.shoalwatch.ui.MainActivity

/**
 * The download notices. **Fixed words only:** never a title, a profile name or an id, so a shared tablet's lock screen and
 * notification shade never say what a profile is downloading; the public version says only "Shoalwatch is downloading".
 * A tap is an explicit, immutable `PendingIntent` to [MainActivity] with `open_tab=downloads` and nothing else. Downloads
 * run whether or not notifications are allowed (the foreground notice still shows in the Task Manager).
 */
internal class DownloadNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.download_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** The foreground notice before the first item starts. */
    fun starting(): Notification = base(context.getString(R.string.download_public)).setOngoing(true).setProgress(0, 0, true).build()

    fun running(position: Int, of: Int, progress: Float?): Notification =
        base(context.getString(R.string.download_running, position, of)).setOngoing(true)
            .setProgress(PROGRESS_MAX, ((progress ?: 0f).coerceIn(0f, 1f) * PROGRESS_MAX).toInt(), progress == null)
            .build()

    /** WaitingForWifi, WaitingForConnection or PausedByTimeLimit. */
    fun waiting(status: QueueStatus): Notification = base(
        context.getString(
            when (status) {
                QueueStatus.WaitingForWifi -> R.string.download_waiting_wifi
                QueueStatus.PausedByTimeLimit -> R.string.download_paused_time_limit
                else -> R.string.download_waiting_connection
            },
        ),
    ).build()

    /** Posts a waiting notice (its own id, not the foreground one) when notifications are allowed. */
    fun showWaiting(status: QueueStatus) = post(WAITING_ID, waiting(status))

    fun clearWaiting() = manager.cancel(WAITING_ID)

    /** Posts "Downloads finished" when notifications are enabled. */
    fun finished(ready: Int) {
        clearWaiting()
        post(FINISHED_ID, base(context.getString(R.string.download_finished)).setContentText(context.getString(R.string.download_finished_count, ready)).setAutoCancel(true).build())
    }

    @android.annotation.SuppressLint("MissingPermission") // checked: areNotificationsEnabled covers POST_NOTIFICATIONS on API 33+
    private fun post(id: Int, notification: Notification) {
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Unit // permission withdrawn in between: downloads carry on without a notice
        }
    }

    private fun base(title: String): NotificationCompat.Builder = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(
            NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.download_public)).build(),
        )
        .setContentIntent(tap())

    private fun tap(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_TAB, TAB_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ID = 4201
        const val FINISHED_ID = 4202
        const val WAITING_ID = 4203
        const val CHANNEL = "downloads"
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_DOWNLOADS = "downloads"
        private const val PROGRESS_MAX = 100
    }
}
