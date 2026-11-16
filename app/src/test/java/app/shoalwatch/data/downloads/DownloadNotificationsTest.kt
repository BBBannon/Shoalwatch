package app.shoalwatch.data.downloads

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The download notification: plain fixed words only, never a title, a profile, a size or a count (the queue holds every
 * profile's downloads: carried Tasks 9-10 Minor 6), and a tap that can only open the Downloads tab.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = DownloadNotifications(context)

    /** Every text a notification carries, its public version's too. */
    private fun texts(n: Notification): List<String> {
        val all = listOfNotNull(n, n.publicVersion).flatMap { m ->
            @Suppress("DEPRECATION")
            m.extras.keySet().mapNotNull { (m.extras.get(it) as? CharSequence)?.toString() } + listOfNotNull(m.tickerText?.toString())
        }
        return all
    }

    @Test fun theChannelIsQuietAndLow() {
        notifications.ensureChannel()
        val channel = manager.getNotificationChannel(DownloadNotifications.CHANNEL)
        assertEquals("Downloads", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
    }

    /** No position, count or per-item progress: the item downloading may be another profile's. */
    @Test fun runningSaysOnlyDownloadingWithAnIndeterminateBarAndIsPrivate() {
        val n = notifications.running()
        assertEquals("Downloading", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue("indeterminate: no per-item progress", n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals(0, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertTrue("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Shoalwatch is downloading", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(DownloadNotifications.CHANNEL, n.channelId)
    }

    @Test fun theWaitingTextsAreExactlyThePlansWords() {
        assertEquals("Waiting for Wi-Fi", title(notifications.waiting(QueueStatus.WaitingForWifi)))
        assertEquals("Waiting for a connection", title(notifications.waiting(QueueStatus.WaitingForConnection)))
        assertEquals("Downloads paused. Open Shoalwatch to carry on.", title(notifications.waiting(QueueStatus.PausedByTimeLimit)))
    }

    @Test fun finishedIsPostedWithNoCountWhenNotificationsAreAllowed() {
        notifications.finished()
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("Downloads finished", title(posted))
        assertNull("no count", posted.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertFalse("not ongoing", posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
        manager.cancelAll()
        shadowOf(manager).setNotificationsEnabled(false)
        notifications.finished()
        assertTrue("nothing posted when notifications are off", shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun theTapIsAnExplicitImmutableIntentToTheDownloadsTabOnly() {
        for (n in listOf(notifications.running(), notifications.waiting(QueueStatus.WaitingForWifi))) {
            val tap: PendingIntent = assertNotNullAndGet(n.contentIntent)
            val shadow = shadowOf(tap)
            assertTrue("immutable", shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
            assertTrue(shadow.isActivity)
            val intent = shadow.savedIntent
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertEquals(context.packageName, intent.component?.packageName)
            assertEquals(setOf(DownloadNotifications.EXTRA_OPEN_TAB), intent.extras!!.keySet())
            assertEquals("downloads", intent.getStringExtra(DownloadNotifications.EXTRA_OPEN_TAB))
        }
    }

    @Test fun theActivityActsOnlyOnTheExactDownloadsValue() {
        fun tab(value: String?) = MainActivity.requestedTab(android.content.Intent().apply { value?.let { putExtra(DownloadNotifications.EXTRA_OPEN_TAB, it) } })
        assertEquals("downloads", tab("downloads"))
        for (other in listOf(null, "", "Downloads", "downloads ", "home", "player", "downloads/../player")) assertNull(other, tab(other))
        assertNull(MainActivity.requestedTab(null))
    }

    @Test fun noTextNamesAnItemAUserAnIdOrANumber() {
        val all = listOf(notifications.running(), notifications.waiting(QueueStatus.WaitingForWifi),
            notifications.waiting(QueueStatus.PausedByTimeLimit), notifications.starting()).flatMap(::texts)
        notifications.finished()
        val finished = shadowOf(manager).allNotifications.flatMap(::texts)
        for (t in all + finished) {
            assertFalse("an id: $t", Regex("[0-9a-f]{32}").containsMatchIn(t))
            assertTrue("only the plan's words: $t", t in ALLOWED || t.startsWith("android.") || t.isBlank() || t == "true" || t == "false")
        }
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun <T> assertNotNullAndGet(value: T?): T { assertNotNull(value); return value!! }

    private companion object {
        val ALLOWED = setOf(
            "Downloading", "Shoalwatch is downloading", "Waiting for Wi-Fi", "Downloads paused. Open Shoalwatch to carry on.",
            "Downloads finished", "Waiting for a connection",
        )
    }
}
