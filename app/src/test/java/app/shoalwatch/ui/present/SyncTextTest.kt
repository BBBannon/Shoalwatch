package app.shoalwatch.ui.present

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.data.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The sync line's exact words, singular and plural, and "today" versus another day in the phone's time zone. */
@RunWith(RobolectricTestRunner::class)
class SyncTextTest {
    private val london = ZoneId.of("Europe/London")
    private fun strings(locale: Locale = Locale.UK): Strings {
        val base: Context = ApplicationProvider.getApplicationContext()
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return Strings(base.createConfigurationContext(config).resources)
    }
    private val res = strings()

    private fun at(text: String): Long = ZonedDateTime.parse(text).toInstant().toEpochMilli()
    private val now = at("2026-11-16T15:30:00Z[Europe/London]")

    private fun status(waiting: Int = 0, stuck: Int = 0, signIn: Boolean = false, last: Long? = null) = SyncStatus(waiting, stuck, signIn, last)
    private fun words(s: SyncStatus, nowMs: Long = now, zone: ZoneId = london, strings: Strings = res) = SyncText.words(s, nowMs, zone, strings)

    @Test fun nothingWaitingAndNeverSyncedShowsNothing() {
        assertNull(words(SyncStatus.NONE))
    }

    @Test fun nothingWaitingAfterASyncToday() {
        assertEquals(SyncWords("All your changes are on your server.", "Last synced at 14:05"), words(status(last = at("2026-11-16T14:05:00Z[Europe/London]"))))
    }

    @Test fun nothingWaitingAfterASyncOnAnotherDay() {
        assertEquals(SyncWords("All your changes are on your server.", "Last synced on 12 November"), words(status(last = at("2026-11-12T09:00:00Z[Europe/London]"))))
    }

    @Test fun waitingSingularAndPlural() {
        assertEquals(SyncWords("1 change waiting to sync", "They're sent when your server can be reached."), words(status(waiting = 1)))
        assertEquals(SyncWords("3 changes waiting to sync", "They're sent when your server can be reached."), words(status(waiting = 3)))
    }

    @Test fun waitingAddsTheLastSyncOnASecondDetailLine() {
        val w = words(status(waiting = 2, last = at("2026-11-16T14:05:00Z[Europe/London]")))!!
        assertEquals("2 changes waiting to sync", w.main)
        assertEquals("They're sent when your server can be reached.\nLast synced at 14:05", w.detail)
    }

    @Test fun needsSignInSingularAndPlural() {
        assertEquals("Sign in again to send 1 change.", words(status(waiting = 1, signIn = true))!!.main)
        assertEquals("Sign in again to send 4 changes.", words(status(waiting = 4, signIn = true))!!.main)
    }

    @Test fun stuckSingularAndPlural() {
        assertEquals("1 change your server keeps refusing. Shoalwatch will try again later.", words(status(waiting = 1, stuck = 1))!!.main)
        assertEquals("2 changes your server keeps refusing. Shoalwatch will try again later.", words(status(waiting = 2, stuck = 2))!!.main)
    }

    @Test fun stuckWithOthersStillWaitingSaysBoth() {
        val w = words(status(waiting = 5, stuck = 2))!!
        assertEquals("2 changes your server keeps refusing. Shoalwatch will try again later.", w.main)
        assertEquals("3 changes waiting to sync", w.detail)
    }

    @Test fun signInComesBeforeStuck() {
        assertEquals("Sign in again to send 3 changes.", words(status(waiting = 3, stuck = 1, signIn = true))!!.main)
    }

    @Test fun midnightInLondonIsWhereTodayBegins() {
        val justBefore = at("2026-11-15T23:59:00Z[Europe/London]")
        val justAfter = at("2026-11-16T00:01:00Z[Europe/London]")
        val earlyNow = at("2026-11-16T00:05:00Z[Europe/London]")
        assertEquals("Last synced on 15 November", words(status(last = justBefore), earlyNow)!!.detail)
        assertEquals("Last synced at 00:01", words(status(last = justAfter), earlyNow)!!.detail)
    }

    @Test fun aClockChangeDayKeepsLocalTimesAndDays() {
        // 29 March 2026: the clocks go forward at 01:00 GMT. 00:30 GMT and 09:00 BST are the same day; 23:30 BST the day before is not.
        val now = ZonedDateTime.of(2026, 3, 29, 9, 0, 0, 0, london).toInstant().toEpochMilli()
        val early = ZonedDateTime.of(2026, 3, 29, 0, 30, 0, 0, london).toInstant().toEpochMilli()
        val dayBefore = ZonedDateTime.of(2026, 3, 28, 23, 30, 0, 0, london).toInstant().toEpochMilli()
        assertEquals("Last synced at 00:30", words(status(last = early), now)!!.detail)
        assertEquals("Last synced on 28 March", words(status(last = dayBefore), now)!!.detail)
        // 25 October 2026: back at 02:00 BST. 01:30 happens twice; the second (GMT) is still that day.
        val autumnNow = ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, london).toInstant().toEpochMilli()
        val secondHalfPastOne = ZonedDateTime.of(2026, 10, 25, 1, 30, 0, 0, london).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        assertEquals("Last synced at 01:30", words(status(last = secondHalfPastOne), autumnNow)!!.detail)
    }

    @Test fun arabicAndHindiStillGiveDigitsZeroToNine() {
        for (locale in listOf(Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN"))) {
            val previous = Locale.getDefault()
            Locale.setDefault(locale)
            try {
                val w = words(status(waiting = 12, last = at("2026-11-12T14:05:00Z[Europe/London]")), strings = strings(locale))!!
                val all = w.main + w.detail
                assertTrue("$locale: $all", all.contains("12") && all.contains("12 November"))
                assertTrue("$locale: only digits 0-9: $all", all.filter { it.isDigit() }.all { it in '0'..'9' })
                val today = words(status(last = at("2026-11-16T14:05:00Z[Europe/London]")), strings = strings(locale))!!
                assertEquals("Last synced at 14:05", today.detail)
            } finally {
                Locale.setDefault(previous)
            }
        }
    }
}
