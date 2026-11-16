package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.PlaybackFakes.ITEM
import app.shoalwatch.data.playback.PlaybackFakes.PSID
import app.shoalwatch.data.playback.PlaybackFakes.SOURCE
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.address
import app.shoalwatch.ui.browse.AMY
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

    private fun kotlinx.coroutines.test.TestScope.reports() = ServerPlaybackReports(gateway, playstate)

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

    @Test fun stoppedSendsThenRecordsTheStopLocally() = runTest {
        val r = reports()
        assertEquals(ReportOutcome.Sent, r.stopped(BEN, report(61_000), finished = false))
        assertEquals(listOf("stopped@61000"), gateway.calls)
        assertEquals(listOf("stop:ben:$ITEM:61000:false"), playstate.calls)
    }

    @Test fun aFinishedStopIsRecordedAsFinished() = runTest {
        reports().stopped(BEN, report(5_000_000), finished = true)
        assertEquals(listOf("stop:ben:$ITEM:5000000:true"), playstate.calls)
    }

    @Test fun aStopTheServerCouldNotBeReachedForIsSavedAsUnsentForTheOutbox() = runTest {
        for (kind in listOf(AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT, AppError.Kind.SERVER_ERROR)) {
            playstate.calls.clear()
            offline(kind)
            assertEquals(ReportOutcome.NotSent(kind), reports().stopped(BEN, report(61_000), finished = false))
            assertEquals(kind.name, listOf("stop:ben:$ITEM:61000:false:unsent"), playstate.calls)
        }
        playstate.calls.clear()
        reports().stopped(BEN, report(5_000_000), finished = true)
        assertEquals(listOf("stop:ben:$ITEM:5000000:true:unsent"), playstate.calls)
    }

    @Test fun anyOtherFailureIsSavedOnThePhoneOnlyAsBefore() = runTest {
        offline(AppError.Kind.FORBIDDEN)
        reports().stopped(BEN, report(61_000), finished = false)
        assertEquals(listOf("stop:ben:$ITEM:61000:false"), playstate.calls)
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

    @Test fun aStopFailingBecauseTheAccountIsSignedOutIsNotKeptOrSavedLocally() = runTest {
        val r = reports()
        offline(AppError.Kind.NOT_SIGNED_IN)
        assertEquals(ReportOutcome.NotSent(AppError.Kind.NOT_SIGNED_IN), r.stopped(BEN, report(), finished = false)); runCurrent()
        assertEquals(1, gateway.calls.size)
        assertTrue("nothing written for a signed-out account", playstate.calls.isEmpty())
    }

    @Test fun aStopForAnAccountForgottenWhileItWasBeingSentIsNotKept() = runTest {
        val r = reports()
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.gate = held
        offline()
        val stopping = backgroundScope.async { r.stopped(BEN, report(), finished = false) }
        runCurrent()
        r.forget(BEN.serverId, BEN.userId)
        gateway.gate = null; held.complete(Unit); stopping.await()
        assertEquals(1, gateway.calls.size)
        assertTrue("nothing written for an account that went", playstate.calls.isEmpty())
    }

    @Test fun forgettingAnotherAccountWhileAStopIsSentLeavesThatStopKeptAndSaved() = runTest {
        val r = reports()
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.gate = held
        offline()
        val stopping = backgroundScope.async { r.stopped(BEN, report(61_000), finished = false) }
        runCurrent()
        r.forget(AMY.serverId, AMY.userId) // another account going (a 401, a sign-out) is not Ben's business
        gateway.gate = null; held.complete(Unit); stopping.await()
        assertEquals("saved, and queued, for Ben", listOf("stop:ben:$ITEM:61000:false:unsent"), playstate.calls)
    }
}
