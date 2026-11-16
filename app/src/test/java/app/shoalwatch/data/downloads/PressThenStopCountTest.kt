package app.shoalwatch.data.downloads

import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.library.PlaystateLanding
import app.shoalwatch.data.playback.LocalPlaybackReports
import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.PlaybackReport
import app.shoalwatch.data.sync.FakeSyncGateway
import app.shoalwatch.data.sync.OfflinePlays
import app.shoalwatch.data.sync.StopsInFlight
import app.shoalwatch.data.sync.SyncSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasks 11-12 review I1: "Watched — delete download" pressed in the player, then the same play's finished stop, is ONE watch:
 * through the real outbox, sender and conflict rule to a server that starts at count 0, it ends at count 1, online or offline.
 * A process that dies between the press and the stop still gets "watched" to the server (offline_plays recovery: PLAYED, never a count).
 */
class PressThenStopCountTest : ReleaseTestBase() {
    private lateinit var server: FakeSyncGateway
    private lateinit var sender: SyncSender
    private lateinit var plays: OfflinePlays
    private lateinit var reports: LocalPlaybackReports
    private lateinit var pressing: HousekeepingRepository

    @Before fun openServer() {
        server = FakeSyncGateway()
        sender = SyncSender(outbox, server, PlaystateLanding(repo, outbox, db.offlinePlayDao()) { now }, { true }, {}, appScope, { now }, Dispatchers.IO, StopsInFlight())
        plays = OfflinePlays(db.offlinePlayDao(), { now }, outbox, db.itemDao())
        // As the app wires it: the player's copy is open while it plays, and the press knows it (HousekeepingParts).
        pressing = HousekeepingRepository(
            releases, db.housekeepingDao(), playstate, ReleasedFiles(paths, playing, appScope), { _, _ -> }, { true }, appScope,
            volumes = fake.volumes, remove = { _, _ -> error("not used") },
            inPlayer = { s, id -> playing.watchedWhileOpen(s.userId, DownloadKey(s.serverId, id)) },
            markHere = { s, id -> plays.watched(s, id); playstate.markWatchedHere(s, id) },
        )
        reports = LocalPlaybackReports(plays, playstate, { now }, playing, pressing::onLocalFinished)
    }

    private val report get() = PlaybackReport(film.id, film.id, null, PlayMethod.LOCAL, 5_000_000, paused = false, audioIndex = 1, subtitleIndex = null)

    private fun drain() = runBlocking { withTimeout(10_000) { sender.kick(a).await() } }

    private fun countRows(kind: String) = count("sync_outbox", "itemId = '${film.id}' AND kind = '$kind'")

    /** [between]: what happens between the press and the stop (online: the outbox sends at once). */
    private fun pressThenStop(between: () -> Unit = {}) = runBlocking {
        seen(a, film)
        downloaded(a, film)
        reports.started(a, report)
        assertEquals(ReleaseOutcome.DELETED, pressing.release(a, film.id, together = false))
        assertTrue("watched on this phone at once", playedHere(a, film))
        settled()
        between()
        reports.stopped(a, report, finished = true)
        settled()
    }

    @Test fun onlinePressThenStopCountsOnce() {
        pressThenStop(between = { drain() })
        drain()
        assertEquals(1, server.copy(a, film.id).playCount)
        assertTrue(server.copy(a, film.id).played)
        assertEquals("nothing left to send", 0, count("sync_outbox"))
    }

    @Test fun offlinePressThenStopCountsOnceWhenItIsSent() {
        pressThenStop() // nothing sends until the connection is back: every row waits in order
        assertEquals("no PLAYED tick on top of the stop's PLAY", 0, countRows("PLAYED"))
        assertEquals(1, countRows("PLAY"))
        drain()
        assertEquals(1, server.copy(a, film.id).playCount)
        assertTrue(server.copy(a, film.id).played)
    }

    @Test fun aProcessThatDiesBetweenThePressAndTheStopStillSendsWatched() = runBlocking {
        seen(a, film)
        downloaded(a, film)
        reports.started(a, report)
        reports.progress(a, report)
        pressing.release(a, film.id, together = false)
        settled()
        // The process dies here: no stop. The next start recovers what offline_plays holds.
        assertEquals(1, plays.recoverUnqueued())
        assertEquals("recovered as the idempotent tick, never a count", 1, countRows("PLAYED"))
        drain()
        assertTrue(server.copy(a, film.id).played)
        assertEquals(1, server.copy(a, film.id).playCount)
    }

    @Test fun aReleaseFromOutsideThePlayerStillQueuesTheTick() {
        seen(a, film)
        downloaded(a, film)
        runBlocking { pressing.release(a, film.id, together = false) }
        settled()
        assertEquals(1, countRows("PLAYED"))
    }

    @Test fun theEveryoneRuleIsToldDirectlyThatThePressingProfileHasWatched() = runBlocking {
        sharing(true, SharedDelete.EVERYONE)
        seen(a, film)
        downloaded(a, film)
        reports.started(a, report)
        // The local mark is not what the rule reads: with it taken back, the rule must still count this profile as watched.
        val direct = HousekeepingRepository(
            releases, db.housekeepingDao(), playstate, ReleasedFiles(paths, playing, appScope), { _, _ -> }, { true }, appScope,
            volumes = fake.volumes, remove = { _, _ -> error("not used") },
            inPlayer = { s, id -> playing.watchedWhileOpen(s.userId, DownloadKey(s.serverId, id)) }, markHere = { _, _ -> },
        )
        assertEquals(ReleaseOutcome.DELETED, direct.release(a, film.id, together = false))
    }
}
