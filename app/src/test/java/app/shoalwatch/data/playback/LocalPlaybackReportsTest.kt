package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.DownloadTestBase
import app.shoalwatch.data.library.LocalPlaystate
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.downloads.LocalPlaying
import app.shoalwatch.data.sync.OfflinePlays
import app.shoalwatch.data.sync.OutboxStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A LOCAL play's reports stay on the phone: `offline_plays` at every report, the local stop at the end; the rest go to the server. */
class LocalPlaybackReportsTest : DownloadTestBase() {
    private val film get() = id("film")
    private val local = mutableListOf<String>()
    private var localFails: Throwable? = null
    private val playstate = object : LocalPlaystate {
        override suspend fun recordLocalStop(s: Session, itemId: String, positionMs: Long, finished: Boolean) {
            // The offline play must already be there, so the marker this leaves is one the sweeps spare.
            val play = db.offlinePlayDao().get(s.serverId, s.userId, itemId)
            local += "stop@$positionMs${if (finished) " finished" else ""} play=${play?.positionMs}"
            localFails?.let { throw it }
        }
    }
    private val playing = LocalPlaying()
    private val finishedFor = mutableListOf<Pair<Session, String>>()
    private val reports by lazy {
        LocalPlaybackReports(OfflinePlays(db.offlinePlayDao(), { clock }, OutboxStore(db) { clock }, db.itemDao()), playstate, { clock }, playing) { s, item -> finishedFor += s to item }
    }

    private fun report(s: String = film, ms: Long, method: PlayMethod = PlayMethod.LOCAL) =
        PlaybackReport(s, PlaybackFakes.SOURCE, null, method, ms, paused = false, audioIndex = 1, subtitleIndex = null)

    private fun position(s: Session) = runBlocking { db.offlinePlayDao().get(s.serverId, s.userId, film)?.positionMs }

    @Test fun startAndProgressWriteThePositionPerProfile() = runBlocking {
        assertEquals(ReportOutcome.Sent, reports.started(s1u1, report(ms = 0)))
        reports.progress(s1u1, report(ms = 10_000))
        reports.progress(s1u2, report(ms = 77_000))
        assertEquals(10_000L, position(s1u1))
        assertEquals(77_000L, position(s1u2))
        assertTrue("no stop yet", local.isEmpty())
    }

    @Test fun aStopWritesTheOfflinePlayFirstThenTheLocalStop() = runBlocking {
        reports.stopped(s1u1, report(ms = 61_000), finished = false)
        assertEquals(listOf("stop@61000 play=61000"), local)
        reports.stopped(s1u1, report(ms = 5_000_000), finished = true)
        assertTrue(runBlocking { db.offlinePlayDao().get(s1u1.serverId, s1u1.userId, film) }!!.played)
        reports.stopped(s1u1, report(ms = 1_000), finished = false)
        assertTrue("watched is never undone by a later unfinished stop", runBlocking { db.offlinePlayDao().get(s1u1.serverId, s1u1.userId, film) }!!.played)
    }

    @Test fun aFailureIsNotSentNeverThrown() = runBlocking {
        localFails = AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        assertEquals(ReportOutcome.NotSent(AppError.Kind.SERVER_NOT_FOUND), reports.stopped(s1u1, report(ms = 1), finished = false))
    }

    @Test fun aFinishedStopCallsOnFinishedOnceAndAnUnfinishedOneDoesNot() = runBlocking {
        reports.started(s1u1, report(ms = 0))
        reports.stopped(s1u1, report(ms = 61_000), finished = false)
        assertTrue(finishedFor.isEmpty())
        reports.started(s1u1, report(ms = 0))
        reports.stopped(s1u1, report(ms = 5_000_000), finished = true)
        assertEquals(listOf(s1u1 to film), finishedFor)
    }

    @Test fun aFinishedStopThatCouldNotBeSavedDoesNotCallOnFinished() = runBlocking {
        localFails = AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        reports.started(s1u1, report(ms = 0))
        reports.stopped(s1u1, report(ms = 5_000_000), finished = true)
        assertTrue(finishedFor.isEmpty())
        assertFalse("the copy is let go even so", playing.isOpen(DownloadKey(s1u1.serverId, film)))
    }

    @Test fun theCopyIsOpenFromTheStartToTheStopAndTwoHoldersPairUp() = runBlocking {
        val key = DownloadKey(s1u1.serverId, film)
        var released = 0
        reports.started(s1u1, report(ms = 0))
        reports.started(s1u2, report(ms = 0)) // another profile's player on the same copy
        playing.whenClosed(key) { released++ }
        assertTrue(playing.isOpen(key))
        reports.stopped(s1u1, report(ms = 1_000), finished = false)
        assertEquals("one holder is still playing it", 0, released)
        reports.stopped(s1u2, report(ms = 2_000), finished = false)
        assertEquals(1, released)
        assertFalse(playing.isOpen(key))
        playing.whenClosed(key) { released++ }
        assertEquals("not open: at once", 2, released)
    }

    @Test fun theRowsGoWithTheAccount() = runBlocking {
        reports.progress(s1u1, report(ms = 10_000))
        servers.removeAccount(s1u1.serverId, s1u1.userId)
        assertEquals(null, position(s1u1))
    }

    @Test fun routedReportsSendOnlyWhatIsNotLocal() = runBlocking {
        val server = FakePlaybackReports()
        val toPhone = FakePlaybackReports()
        val routed = RoutedPlaybackReports(server, toPhone)
        routed.started(s1u1, report(ms = 0))
        routed.progress(s1u1, report(ms = 1_000))
        routed.stopped(s1u1, report(ms = 2_000), finished = true)
        routed.started(s1u1, report(ms = 0, method = PlayMethod.DIRECT_PLAY))
        routed.ping(s1u1, PlaybackFakes.PSID)
        assertEquals(listOf("started@0", "progress@1000", "stopped@2000 finished"), toPhone.calls)
        assertEquals(listOf("started@0", "ping:${PlaybackFakes.PSID}"), server.calls)
    }
}
