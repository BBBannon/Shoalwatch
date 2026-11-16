package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.downloads.LocalPlaying
import app.shoalwatch.data.library.PlaystateTestBase
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.OfflinePlays
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's stops through the real tidy-up actions and outbox: a stop the server missed is queued with its own time
 * (Milestone 3's carried M-2 and M-3), and every play of a saved copy is queued, online or not (Milestone 4 sent none).
 */
class PlaybackOutboxTest : PlaystateTestBase() {
    private val playback = FakePlaybackGateway()
    private val server by lazy { ServerPlaybackReports(playback, playstate, stopsInFlight, { now }) { s -> sender.kick(s) } }
    private val finished = mutableListOf<Pair<Session, String>>()
    private val local by lazy {
        LocalPlaybackReports(OfflinePlays(db.offlinePlayDao(), { now }, outbox, db.itemDao()), playstate, { now }, LocalPlaying()) { s, item -> finished += s to item }
    }

    private data class Row(val userId: String, val kind: String, val flag: String?, val positionMs: Long?, val occurredAt: Long, val fromOfflinePlay: Boolean)

    private fun rows(): List<Row> = db.openHelper.readableDatabase.query("SELECT userId, kind, flag, positionMs, occurredAt, fromOfflinePlay FROM sync_outbox ORDER BY seq").use { c ->
        buildList { while (c.moveToNext()) add(Row(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getLong(3), c.getLong(4), c.getInt(5) == 1)) }
    }

    private fun report(positionMs: Long, method: PlayMethod = PlayMethod.DIRECT_PLAY) =
        PlaybackReport(id("film"), PlaybackFakes.SOURCE, if (method == PlayMethod.LOCAL) null else PlaybackFakes.PSID, method, positionMs, paused = false, audioIndex = 1, subtitleIndex = null)

    private fun unreachable() {
        playback.failOps["stopped"] = AppError.of(AppError.Kind.OFFLINE)
        gateway.failAll = AppError.Kind.OFFLINE // and the outbox cannot send either, so the rows stay to be read
    }

    /** Waits for a signal (no fixed sleep): fails after five seconds rather than hanging. */
    private suspend fun until(what: String, test: () -> Boolean) {
        kotlinx.coroutines.withTimeoutOrNull(5_000) { while (!test()) kotlinx.coroutines.yield() } ?: throw AssertionError("never: $what")
    }

    @Test fun aDrainAlreadyRunningNeverSendsAFinishedStopsTickBeforeTheReport() = runBlocking {
        save(film("film", ud(positionMs = 60_000)), film("other", ud()))
        val busy = holdCalls("setPlayed:${id("other")}")
        playstate.setPlayed(s1u1, id("other"), true) // a drain starts for the profile and is held on its first row
        until("the drain is sending") { gateway.calls.contains("setPlayed:${id("other")}:true") }
        playback.gate = kotlinx.coroutines.CompletableDeferred()
        val stop = async(kotlinx.coroutines.Dispatchers.IO) { server.stopped(s1u1, report(5_000_000), finished = true) }
        until("the stop report is in flight") { playback.calls.contains("stopped@5000000") } // record #1 has queued its PLAYED tick
        busy.release()
        until("the running drain has ended") { !sender.isDraining(s1u1) }
        assertEquals("nothing for the item while its report is in flight", 0, gateway.count("setPlayed:${id("film")}"))
        assertEquals(1, rows().count { it.kind == "PLAYED" })
        playback.gate?.complete(Unit)
        stop.await()
        settle()
        assertEquals("sent once, after the report", 1, gateway.count("setPlayed:${id("film")}"))
        assertEquals(0, queued())
    }

    @Test fun anUnsentUnfinishedStopQueuesOnePositionWithTheReportsPositionAndTime() = runBlocking {
        save(film("film", ud(positionMs = 0)))
        unreachable()
        val at = now
        server.stopped(s1u1, report(1_234_000), finished = false)
        settle()
        assertEquals(listOf(Row(s1u1.userId, "POSITION", null, 1_234_000, at, false)), rows())
        assertEquals(1_234_000, userData("film")!!.positionMs)
    }

    @Test fun anUnsentFinishedStopQueuesPlayedAndShowsWatchedAtOnce() = runBlocking {
        save(film("film", ud(positionMs = 60_000)))
        unreachable()
        server.stopped(s1u1, report(5_000_000), finished = true)
        settle()
        assertEquals(listOf("PLAYED"), rows().map { it.kind })
        assertEquals("1", rows().single().flag)
        assertTrue(userData("film")!!.played)
    }

    @Test fun aSentStopQueuesNothing() = runBlocking {
        save(film("film", ud(positionMs = 0)))
        server.stopped(s1u1, report(61_000), finished = false)
        server.stopped(s1u1, report(5_000_000), finished = true)
        settle()
        assertEquals(0, queued())
        assertEquals(listOf("stopped@61000", "stopped@5000000"), playback.calls)
        assertTrue(userData("film")!!.played)
    }

    @Test fun aLaterUnsentStopForTheSameItemReplacesTheEarlierRowSoAnOldStopCanNeverOverwriteANewerPosition() = runBlocking {
        save(film("film", ud(positionMs = 0)))
        unreachable()
        server.stopped(s1u1, report(100_000), finished = false)
        now += 60_000
        server.stopped(s1u1, report(900_000), finished = false)
        settle()
        assertEquals(listOf(900_000L), rows().map { it.positionMs })
    }

    @Test fun aStopWhileTheSignInIsRejectedIsKeptForTheNextSignIn() = runBlocking {
        save(film("film", ud(positionMs = 0)))
        playback.failOps["stopped"] = AppError.of(AppError.Kind.NOT_SIGNED_IN)
        gateway.failAll = AppError.Kind.NOT_SIGNED_IN
        server.stopped(s1u1, report(61_000), finished = false)
        settle()
        assertEquals(listOf(61_000L), rows().map { it.positionMs })
        assertEquals(61_000, userData("film")!!.positionMs)
    }

    /** Tasks 6-8 review M3: the app closed while the stop was being sent still has it queued, with the stop's own time. */
    @Test fun aStopCutOffMidSendIsStillQueuedWithItsTime() = runBlocking {
        save(film("film", ud(positionMs = 0)))
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        playback.gate = held
        val at = now
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val stopping = scope.async { server.stopped(s1u1, report(1_234_000), finished = false) }
        kotlinx.coroutines.withTimeout(5_000) { while (rows().isEmpty()) kotlinx.coroutines.delay(5) }
        stopping.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        assertEquals(listOf(Row(s1u1.userId, "POSITION", null, 1_234_000, at, false)), rows())
        assertEquals(1_234_000, userData("film")!!.positionMs)
        sender.kick(s1u1).await() // a later drain (the next start, the job) sends it
        assertEquals(0, queued())
        assertEquals(listOf("setPosition:${id("film")}:1234000"), gateway.calls.filter { it.startsWith("setPosition") })
    }

    @Test fun aFinishedLocalStopQueuesExactlyOnePlayAtTheStopsTimeAndCallsOnFinishedOnce() = runBlocking {
        save(film("film", ud(positionMs = 0)))
        gateway.failAll = AppError.Kind.OFFLINE
        local.started(s1u1, report(0, PlayMethod.LOCAL))
        val at = now
        local.stopped(s1u1, report(5_000_000, PlayMethod.LOCAL), finished = true)
        settle()
        assertEquals(listOf(Row(s1u1.userId, "PLAY", null, null, at, true)), rows())
        assertEquals(listOf(s1u1 to id("film")), finished)
        assertTrue(userData("film")!!.played)
    }

    @Test fun anUnfinishedLocalStopQueuesItsPositionAndDoesNotCallOnFinished() = runBlocking {
        save(film("film", ud(positionMs = 0)))
        gateway.failAll = AppError.Kind.OFFLINE
        local.started(s1u1, report(0, PlayMethod.LOCAL))
        local.stopped(s1u1, report(61_000, PlayMethod.LOCAL), finished = false)
        settle()
        assertEquals(listOf(Row(s1u1.userId, "POSITION", null, 61_000, rows().single().occurredAt, true)), rows())
        assertTrue(finished.isEmpty())
    }

    @Test fun aSavedCopyPlayedWhileOnlineIsSentToTheServerToo() = runBlocking {
        save(film("film", ud(positionMs = 0)))
        local.started(s1u1, report(0, PlayMethod.LOCAL))
        local.stopped(s1u1, report(61_000, PlayMethod.LOCAL), finished = false)
        settle()
        assertEquals("the server learns the position", listOf("setPosition:${id("film")}:61000"), gateway.calls.filter { it.startsWith("setPosition") })
        assertEquals(0, queued())
        assertEquals("the covered offline play goes", 0, count("offline_plays"))
        assertTrue("no session report is ever sent for a LOCAL play", playback.calls.isEmpty())
    }

    @Test fun aSecondProfilesLocalPlayNeverTouchesTheFirstsRows() = runBlocking {
        save(film("film", ud(positionMs = 0)))
        save(film("film", ud(positionMs = 7_000)), s = s1u2)
        gateway.failAll = AppError.Kind.OFFLINE
        local.started(s1u2, report(0, PlayMethod.LOCAL))
        local.stopped(s1u2, report(5_000_000, PlayMethod.LOCAL), finished = true)
        settle()
        assertEquals(listOf(s1u2.userId), rows().map { it.userId })
        assertEquals(0, userData("film")!!.positionMs)
        assertEquals(false, userData("film")!!.played)
        assertEquals(null, userData("film")!!.pendingSyncAt)
        assertEquals(null, db.offlinePlayDao().get(s1u1.serverId, s1u1.userId, id("film")))
    }
}
