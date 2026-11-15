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

/** The seam's server half: reports sent best-effort, the stop also saved locally, one unsent stop kept and resent once. */
class ServerPlaybackReportsTest {
    private val gateway = FakePlaybackGateway()
    private val playstate = FakePlaystate()
    private val connections = FakeConnections()
    private val online = Connection.Online(address("https://media.example:8920"), isHome = false)
    private val server = BEN.serverId

    private fun report(positionMs: Long = 61_000, item: String = ITEM) =
        PlaybackReport(item, SOURCE, PSID, PlayMethod.TRANSCODE, positionMs, paused = false, audioIndex = 1, subtitleIndex = null)

    private fun kotlinx.coroutines.test.TestScope.reports() = ServerPlaybackReports(gateway, playstate, connections, backgroundScope)

    private fun offline(kind: AppError.Kind = AppError.Kind.OFFLINE) { gateway.failOps["stopped"] = AppError.of(kind) }

    /** A network change, seen, and then a new verification. */
    private fun kotlinx.coroutines.test.TestScope.reconnect() {
        connections.stateFor(server).value = Connection.Checking()
        runCurrent()
        connections.stateFor(server).value = online
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

    @Test fun theStopIsRecordedLocallyEvenWhenSendingFailed() = runTest {
        offline()
        assertEquals(ReportOutcome.NotSent(AppError.Kind.OFFLINE), reports().stopped(BEN, report(61_000), finished = false))
        assertEquals(listOf("stop:ben:$ITEM:61000:false"), playstate.calls)
    }

    @Test fun aStopThatCouldNotBeSentIsResentOnceWhenTheServerIsBackWithoutItsPlaySession() = runTest {
        connections.stateFor(server).value = Connection.Checking()
        val r = reports()
        offline()
        r.stopped(BEN, report(61_000), finished = false); runCurrent()
        gateway.failOps.clear()
        connections.stateFor(server).value = online; runCurrent()
        assertEquals(listOf("stopped@61000", "stopped@61000 nosession"), gateway.calls)
        assertEquals(BEN, gateway.reports.last().first)
        reconnect(); runCurrent()
        assertEquals("resent once, never again", 2, gateway.calls.size)
        assertEquals("recorded locally once", 1, playstate.calls.size)
    }

    @Test fun aStopFailedWhileOnlineWaitsForTheNextReconnection() = runTest {
        connections.stateFor(server).value = online
        val r = reports()
        offline(AppError.Kind.SERVER_ERROR)
        r.stopped(BEN, report(61_000), finished = false); runCurrent()
        gateway.failOps.clear()
        assertEquals(1, gateway.calls.size)
        reconnect(); runCurrent()
        assertEquals(listOf("stopped@61000", "stopped@61000 nosession"), gateway.calls)
    }

    @Test fun aFailedResendIsDropped() = runTest {
        val r = reports()
        offline()
        r.stopped(BEN, report(), finished = false); runCurrent()
        reconnect(); runCurrent()
        reconnect(); runCurrent()
        assertEquals(2, gateway.calls.size)
    }

    @Test fun aSecondStopForTheSameItemReplacesThePendingOne() = runTest {
        val r = reports()
        offline()
        r.stopped(BEN, report(61_000), finished = false)
        r.stopped(BEN, report(75_000), finished = false); runCurrent()
        gateway.failOps.clear()
        reconnect(); runCurrent()
        assertEquals(listOf("stopped@61000", "stopped@75000", "stopped@75000 nosession"), gateway.calls)
    }

    @Test fun aStopSentLaterForTheSameItemCancelsThePendingOne() = runTest {
        val r = reports()
        offline()
        r.stopped(BEN, report(61_000), finished = false)
        gateway.failOps.clear()
        r.stopped(BEN, report(75_000), finished = false); runCurrent()
        reconnect(); runCurrent()
        assertEquals(listOf("stopped@61000", "stopped@75000"), gateway.calls)
    }

    @Test fun pendingStopsAreKeptPerSessionAndResentWithTheirOwn() = runTest {
        val r = reports()
        offline()
        r.stopped(BEN, report(61_000), finished = false)
        r.stopped(AMY, report(12_000), finished = false); runCurrent()
        gateway.failOps.clear()
        reconnect(); runCurrent()
        val resent = gateway.reports.drop(2)
        assertEquals(setOf(BEN to 61_000L, AMY to 12_000L), resent.map { it.first to it.second.positionMs }.toSet())
    }

    @Test fun forgetDropsTheAccountsPendingStopAndNothingIsSentAfterwards() = runTest {
        val r = reports()
        offline()
        r.stopped(BEN, report(61_000), finished = false)
        r.stopped(AMY, report(12_000), finished = false); runCurrent()
        gateway.failOps.clear()
        r.forget(BEN.serverId, BEN.userId)
        reconnect(); runCurrent()
        assertEquals(listOf(AMY), gateway.reports.drop(2).map { it.first })
    }

    @Test fun forgettingTheServerDropsEveryAccountsPendingStop() = runTest {
        val r = reports()
        offline()
        r.stopped(BEN, report(61_000), finished = false)
        r.stopped(AMY, report(12_000), finished = false); runCurrent()
        gateway.failOps.clear()
        r.forget(server)
        reconnect(); runCurrent()
        assertEquals(2, gateway.calls.size)
    }

    @Test fun aStopFailingBecauseTheAccountIsSignedOutIsNotKeptOrSavedLocally() = runTest {
        val r = reports()
        offline(AppError.Kind.NOT_SIGNED_IN)
        assertEquals(ReportOutcome.NotSent(AppError.Kind.NOT_SIGNED_IN), r.stopped(BEN, report(), finished = false)); runCurrent()
        gateway.failOps.clear()
        reconnect(); runCurrent()
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
        gateway.failOps.clear()
        reconnect(); runCurrent()
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
        assertEquals("saved locally for Ben", 1, playstate.calls.size)
        gateway.failOps.clear()
        reconnect(); runCurrent()
        assertEquals("kept and resent for Ben", listOf(BEN to 61_000L), gateway.reports.drop(1).map { it.first to it.second.positionMs })
    }
}
