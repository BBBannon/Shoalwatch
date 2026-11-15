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

/** The download notification: plain fixed words only, never a title or a profile, and a tap that can only open the Downloads tab. */
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

    @Test fun runningSaysWhereItIsWithAProgressBarAndIsPrivate() {
        val n = notifications.running(3, 12, 0.25f)
        assertEquals("Downloading 3 of 12", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(100, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(25, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertTrue("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Shoalwatch is downloading", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(DownloadNotifications.CHANNEL, n.channelId)
        assertTrue("indeterminate while unknown", notifications.running(1, 1, null).extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test fun theWaitingTextsAreExactlyThePlansWords() {
        assertEquals("Waiting for Wi-Fi", title(notifications.waiting(QueueStatus.WaitingForWifi)))
        assertEquals("Waiting for a connection", title(notifications.waiting(QueueStatus.WaitingForConnection)))
        assertEquals("Downloads paused. Open Shoalwatch to carry on.", title(notifications.waiting(QueueStatus.PausedByTimeLimit)))
    }

    @Test fun finishedIsPostedWithTheCountWhenNotificationsAreAllowed() {
        notifications.finished(4)
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("Downloads finished", title(posted))
        assertEquals("4 ready to watch offline", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse("not ongoing", posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
        manager.cancelAll()
        shadowOf(manager).setNotificationsEnabled(false)
        notifications.finished(4)
        assertTrue("nothing posted when notifications are off", shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun theTapIsAnExplicitImmutableIntentToTheDownloadsTabOnly() {
        for (n in listOf(notifications.running(1, 2, null), notifications.waiting(QueueStatus.WaitingForWifi))) {
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

    @Test fun noTextNamesAnItemAUserOrAnId() {
        val all = listOf(notifications.running(2, 5, 0.5f), notifications.waiting(QueueStatus.WaitingForWifi),
            notifications.waiting(QueueStatus.PausedByTimeLimit), notifications.starting()).flatMap(::texts)
        notifications.finished(2)
        val finished = shadowOf(manager).allNotifications.flatMap(::texts)
        for (t in all + finished) {
            assertFalse("an id: $t", Regex("[0-9a-f]{32}").containsMatchIn(t))
            assertTrue("only the plan's words: $t", t in ALLOWED || t.startsWith("android.") || t.isBlank() || t == "true" || t == "false" || t.all { it.isDigit() })
        }
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun <T> assertNotNullAndGet(value: T?): T { assertNotNull(value); return value!! }

    private companion object {
        val ALLOWED = setOf(
            "Downloading 2 of 5", "Shoalwatch is downloading", "Waiting for Wi-Fi", "Downloads paused. Open Shoalwatch to carry on.",
            "Downloads finished", "2 ready to watch offline", "Waiting for a connection",
        )
    }
}
