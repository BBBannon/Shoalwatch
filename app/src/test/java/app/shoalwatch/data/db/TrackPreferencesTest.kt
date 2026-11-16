package app.shoalwatch.data.db

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.data.playback.TrackChoice
import app.shoalwatch.data.playback.TrackPreferences
import app.shoalwatch.testId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Track choices per (server, user, series) in in-memory Room, through the real [app.shoalwatch.data.servers.ServerStore]. */
class TrackPreferencesTest : LibraryTestBase() {
    private var now = 1_000L
    private val prefs by lazy { TrackPreferences(db.trackChoiceDao()) { now } }
    private val series = testId("series")
    private val other = testId("other-series")
    private val japanese = TrackChoice("jpn", "eng", subtitlesOff = false, forced = false, hearingImpaired = false)
    private val off = TrackChoice("eng", null, subtitlesOff = true, forced = false, hearingImpaired = false)

    @Test fun rememberThenGetForTheSameServerUserAndSeries() = runBlocking {
        prefs.remember(s1u1, series, japanese)
        assertEquals(japanese, prefs.get(s1u1, series))
        assertNull("another series of the same user", prefs.get(s1u1, other))
    }

    @Test fun aLaterChoiceReplacesTheEarlierOneAndRecordsTheTime() = runBlocking {
        prefs.remember(s1u1, series, japanese)
        now = 2_000
        prefs.remember(s1u1, series, off)
        assertEquals(off, prefs.get(s1u1, series))
        assertEquals(1, count("track_choices"))
        assertEquals(1, count("track_choices", "updatedAt = 2000"))
    }

    @Test fun anotherUserOnTheSameServerSeesNothing() = runBlocking {
        prefs.remember(s1u1, series, japanese)
        assertNull("a restricted profile never inherits another profile's choice", prefs.get(s1u2, series))
        assertNull("the same user id on another server is another account", prefs.get(s2u1, series))
        prefs.remember(s1u2, series, off)
        assertEquals(japanese, prefs.get(s1u1, series))
        assertEquals(off, prefs.get(s1u2, series))
    }

    @Test fun removingTheAccountCascadesItsRowsOnly() = runBlocking {
        prefs.remember(s1u1, series, japanese)
        prefs.remember(s1u2, series, off)
        servers.removeAccount(S1, U1)
        assertEquals(0, count("track_choices", "userId = '$U1' AND serverId = '$S1'"))
        assertEquals(off, prefs.get(s1u2, series))
    }

    @Test fun removingTheServerCascadesThroughItsAccounts() = runBlocking {
        prefs.remember(s1u1, series, japanese)
        prefs.remember(s2u1, series, off)
        servers.removeServer(S1)
        assertEquals(1, count("track_choices"))
        assertEquals(off, prefs.get(s2u1, series))
    }

    @Test fun signingInAgainKeepsTheChoice() = runBlocking {
        prefs.remember(s1u1, series, japanese)
        servers.upsertAccount(app.shoalwatch.data.servers.Account(S1, U1, "ann", "a-new-token"), makeActive = true)
        assertEquals(japanese, prefs.get(s1u1, series))
    }

    @Test fun filmsAndMalformedSeriesIdsAreNotRemembered() = runBlocking {
        prefs.remember(s1u1, null, japanese)
        prefs.remember(s1u1, "not-an-id", japanese)
        assertEquals(0, count("track_choices"))
        assertNull(prefs.get(s1u1, null))
    }

    @Test fun aChoiceForAnAccountThatWentIsDroppedWithoutThrowing() = runBlocking {
        servers.removeAccount(S1, U1)
        prefs.remember(s1u1, series, japanese) // foreign key: nothing to attach it to
        assertEquals(0, count("track_choices"))
    }
}
