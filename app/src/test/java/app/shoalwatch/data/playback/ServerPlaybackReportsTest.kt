package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.PlaybackFakes.ITEM
import app.shoalwatch.data.playback.PlaybackFakes.PSID
import app.shoalwatch.data.playback.PlaybackFakes.SOURCE
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.address
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakePlaystate
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The seam's server half: reports sent best-effort, the stop also saved locally, and a stop the server missed handed to the outbox (never resent here). */
class ServerPlaybackReportsTest {
    private val gateway = FakePlaybackGateway()
    private val playstate = FakePlaystate()
    private val connections = FakeConnections()
    private val server = BEN.serverId

    private fun report(positionMs: Long = 61_000, item: String = ITEM) =
        PlaybackReport(item, SOURCE, PSID, PlayMethod.TRANSCODE, positionMs, paused = false, audioIndex = 1, subtitleIndex = null)

    private val kicked = mutableListOf<String>()
    private fun kotlinx.coroutines.test.TestScope.reports() = ServerPlaybackReports(gateway, playstate) { s -> kicked += "kick:" + if (s == BEN) "ben" else "other" }

    private fun offline(kind: AppError.Kind = AppError.Kind.OFFLINE) { gateway.failOps["stopped"] = AppError.of(kind) }

    /** A network change, seen, and then a new verification: nothing here listens any more. */
    private fun kotlinx.coroutines.test.TestScope.reconnect() {
        connections.stateFor(server).value = Connection.Checking()
        runCurrent()
        connections.stateFor(server).value = Connection.Online(address("https://media.example:8920"), isHome = false)
        runCurrent()
    }

    @Test fun startedProgressAndPingAreSent() = runTest {
        val r = reports()
        assertEquals(ReportOutcome.Sent, r.started(BEN, report(0)))
        assertEquals(ReportOutcome.Sent, r.progress(BEN, report(10_000)))
        assertEquals(ReportOutcome.Sent, r.ping(BEN, PSID))
        assertEquals(listOf("start@0", "progress@10000", "ping"), gateway.calls)
        assertTrue(gateway.reports.all { it.first == BEN })
    }

    @Test fun failuresAreNotSentWithTheirKindAndNeverThrow() = runTest {
        val r = reports()
        gateway.failOps["start"] = AppError.of(AppError.Kind.OFFLINE)
        gateway.failOps["progress"] = IOException("reset")
        gateway.failOps["ping"] = IllegalStateException("odd")
        assertEquals(ReportOutcome.NotSent(AppError.Kind.OFFLINE), r.started(BEN, report(0)))
        assertEquals(ReportOutcome.NotSent(AppError.Kind.NETWORK), r.progress(BEN, report(1)))
        assertEquals(ReportOutcome.NotSent(AppError.Kind.UNKNOWN), r.ping(BEN, PSID))
        gateway.failOps["stopped"] = RuntimeException("worse")
        playstate.failWith = AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        assertEquals(ReportOutcome.NotSent(AppError.Kind.UNKNOWN), r.stopped(BEN, report(), finished = false))
    }

    /**
     * Tasks 6-8 review M3: the stop is saved and queued (not yet sent) BEFORE the report leaves, so a process death mid-send
     * cannot lose it; once the server has heard an unfinished stop its queued position goes again (never sent twice).
     */
    @Test fun aHeardStopIsQueuedBeforeTheSendThenTakenBackAndNothingIsKicked() = runTest {
        val r = reports()
        assertEquals(ReportOutcome.Sent, r.stopped(BEN, report(61_000), finished = false))
        assertEquals(listOf("stop:ben:$ITEM:61000:false:unsent", "stop:ben:$ITEM:61000:false"), playstate.calls)
        assertEquals(listOf("stopped@61000"), gateway.calls)
        assertTrue("nothing left to send", kicked.isEmpty())
    }

    @Test fun theStopIsDurableBeforeTheReportLeavesSoAKillMidSendLosesNothing() = runTest {
        val r = reports()
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.gate = held
        val stopping = backgroundScope.async { r.stopped(BEN, report(61_000), finished = false) }
        runCurrent()
        assertEquals("queued while the report is still being sent", listOf("stop:ben:$ITEM:61000:false:unsent"), playstate.calls)
        stopping.cancel() // the process dies mid-send
        runCurrent()
        assertEquals("the queued row stays for the outbox", listOf("stop:ben:$ITEM:61000:false:unsent"), playstate.calls)
        assertTrue("and it is not sent before the report", kicked.isEmpty())
    }

    @Test fun aFinishedStopStaysQueuedAndIsKickedAfterTheReport() = runTest {
        reports().stopped(BEN, report(5_000_000), finished = true)
        assertEquals("PLAYED stays queued (review I1)", listOf("stop:ben:$ITEM:5000000:true:unsent"), playstate.calls)
        assertEquals(listOf("stopped@5000000", "kick:ben"), gateway.calls + kicked)
    }

    @Test fun aStopTheServerCouldNotBeReachedForStaysQueuedAndIsKicked() = runTest {
        for (kind in listOf(AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT, AppError.Kind.SERVER_ERROR)) {
            playstate.calls.clear(); kicked.clear()
            offline(kind)
            assertEquals(ReportOutcome.NotSent(kind), reports().stopped(BEN, report(61_000), finished = false))
            assertEquals(kind.name, listOf("stop:ben:$ITEM:61000:false:unsent"), playstate.calls)
            assertEquals(listOf("kick:ben"), kicked)
        }
    }

    @Test fun anyOtherFailureIsSavedOnThePhoneOnlyAsBefore() = runTest {
        offline(AppError.Kind.FORBIDDEN)
        reports().stopped(BEN, report(61_000), finished = false)
        assertEquals(listOf("stop:ben:$ITEM:61000:false:unsent", "stop:ben:$ITEM:61000:false"), playstate.calls)
        assertTrue(kicked.isEmpty())
    }

    @Test fun thereIsNoResendWhenTheConnectionReturnsTheOutboxDoesIt() = runTest {
        connections.stateFor(server).value = Connection.Checking()
        val r = reports()
        offline()
        r.stopped(BEN, report(61_000), finished = false); runCurrent()
        gateway.failOps.clear()
        reconnect()
        reconnect()
        assertEquals("one stop report, never replayed", listOf("stopped@61000"), gateway.calls)
        assertEquals(1, playstate.calls.size)
    }

    @Test fun aStopWhileTheSignInIsRejectedStaysQueuedForTheNextSignIn() = runTest {
        val r = reports()
        offline(AppError.Kind.NOT_SIGNED_IN)
        assertEquals(ReportOutcome.NotSent(AppError.Kind.NOT_SIGNED_IN), r.stopped(BEN, report(), finished = false)); runCurrent()
        assertEquals(1, gateway.calls.size)
        assertEquals("kept, as the account's other rows are (a removed account's rows go with it)", listOf("stop:ben:$ITEM:61000:false:unsent"), playstate.calls)
    }

    @Test fun aStopThatCannotBeSavedIsStillReported() = runTest {
        playstate.failWith = AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        assertEquals(ReportOutcome.Sent, reports().stopped(BEN, report(61_000), finished = false))
        assertEquals(listOf("stopped@61000"), gateway.calls)
    }
}
