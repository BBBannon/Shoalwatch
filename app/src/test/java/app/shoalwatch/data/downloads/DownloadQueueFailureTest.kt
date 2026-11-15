package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.playback.fixtureInfo
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowLog

/** Every way one item can fail, and what is left in Room and on disk. */
class DownloadQueueFailureTest : DownloadQueueTestBase() {
    private val a get() = s1u1
    private val f1 get() = item("f1")
    private val f2 get() = item("f2")

    private fun failure(item: app.shoalwatch.data.library.Item) = download(item)!!.let { it.state to it.failure }

    @Test fun noOwnerThatCanStillSendIsSignInNeededAndTheNextItemRuns() {
        queued(a, f1)
        queued(s1u2, f2)
        tokens -= a
        playable()
        assertEquals(QueueRun.Drained, drain())
        assertEquals(DownloadState.FAILED to DownloadFailure.SIGN_IN_NEEDED, failure(f1))
        assertEquals(DownloadState.COMPLETE, download(f2)!!.state)
        assertEquals("nothing was asked for f1", listOf(f2.id), files.calls.map { it.itemId })
    }

    @Test fun theEarliestOwnerWithATokenFetches() {
        queued(a, f1)
        clock += 10
        queued(s1u2, f1)
        tokens -= a
        playable()
        drain()
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
    }

    @Test fun unauthorisedWithFactsAlsoRefusedIsSignInNeeded() {
        queued(a, f1)
        playable()
        files.steps += FakeFiles.answer(FetchResult.Unauthorised)
        facts.failWith = AppError.of(AppError.Kind.UNAUTHORISED)
        assertEquals(QueueRun.Drained, drain())
        assertEquals(DownloadState.FAILED to DownloadFailure.SIGN_IN_NEEDED, failure(f1))
        assertEquals("exactly one ServerApi call", listOf(listOf(f1.id)), facts.calls)
    }

    @Test fun unauthorisedWithFactsAnsweringIsServerAuthRefused() {
        queued(a, f1)
        playable()
        facts.allow(f1.id)
        files.steps += FakeFiles.answer(FetchResult.Unauthorised)
        drain()
        assertEquals(DownloadState.FAILED to DownloadFailure.SERVER_AUTH_REFUSED, failure(f1))
    }

    @Test fun unauthorisedWithFactsOfflineIsARetry() {
        queued(a, f1)
        playable()
        files.steps += FakeFiles.answer(FetchResult.Unauthorised)
        facts.failWith = AppError.of(AppError.Kind.OFFLINE)
        assertEquals(QueueRun.Retry(AppError.Kind.OFFLINE), drain())
        assertEquals(DownloadState.QUEUED, download(f1)!!.state)
        assertEquals(1, download(f1)!!.attempts)
    }

    @Test fun aMissingCardIsStorageMissing() {
        val card = tmp.newFolder("card")
        fake.card("CARD-1", card)
        seen(a, f1)
        runBlocking { store.add(a, listOf(newDownload(f1).let { n -> app.shoalwatch.data.db.downloads.DownloadStore.NewDownload(n.itemId, n.kind, n.seriesId, n.seasonId, n.quality, StorageKind.REMOVABLE, "CARD-1", n.runtimeMs, null) })) }
        fake.unmount("CARD-1")
        assertEquals(QueueRun.Drained, drain())
        assertEquals(DownloadState.FAILED to DownloadFailure.STORAGE_MISSING, failure(f1))
        assertTrue(playback.calls.isEmpty())
    }

    @Test fun tooLittleSpaceIsNoSpaceBeforeAnyRequest() {
        queued(a, f1)
        fake.free[null] = (1L shl 30) - 1 // under the internal reserve
        assertEquals(QueueRun.Drained, drain())
        assertEquals(DownloadState.FAILED to DownloadFailure.NO_SPACE, failure(f1))
        assertTrue("nothing asked", playback.calls.isEmpty() && files.calls.isEmpty() && routes.isEmpty())
    }

    @Test fun aSizeThatDoesNotFitIsNoSpaceBeforeTheFileIsAskedFor() {
        queued(a, f1)
        playable() // Size 1,234,567,890
        fake.free[null] = (1L shl 30) + 1_000_000_000L
        drain()
        assertEquals(DownloadState.FAILED to DownloadFailure.NO_SPACE, failure(f1))
        assertTrue(files.calls.isEmpty())
    }

    @Test fun aFirstBadVerdictDownloadsAgainAndASecondIsCheckFailedWithTheFolderEmpty() {
        queued(a, f1)
        playable(2)
        verifier.verdicts += Verdict.Bad("signature")
        verifier.verdicts += Verdict.Bad("signature")
        assertEquals(QueueRun.Drained, drain())
        assertEquals("fetched twice, from zero the second time", listOf(0L, 0L), files.calls.map { it.resumeFrom })
        assertEquals(DownloadState.FAILED to DownloadFailure.CHECK_FAILED, failure(f1))
        assertEquals("nothing left on disk", emptyList<String>(), folder(f1).list()?.toList().orEmpty())
    }

    @Test fun aFirstBadVerdictThenAGoodOneCompletes() {
        queued(a, f1)
        playable(2)
        verifier.verdicts += Verdict.Bad("length")
        drain()
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
        assertEquals(2, files.calls.size)
    }

    @Test fun eightAttemptsOfAnyKindAreServerError() {
        queued(a, f1)
        playable(8)
        repeat(3) { files.steps += FakeFiles.partly(1_000L * (it + 1)) }
        repeat(2) { files.steps += { c -> c.progress.saved(0, null, null, c.restarts + 1); FetchResult.Retry(AppError.Kind.SERVER_ERROR) } }
        repeat(3) { files.steps += FakeFiles.answer(FetchResult.Retry(AppError.Kind.TIMEOUT)) }
        var runs = 0
        while (download(f1)!!.state != DownloadState.FAILED && runs++ < 20) drain()
        assertEquals(DownloadState.FAILED to DownloadFailure.SERVER_ERROR, failure(f1))
        assertTrue("it took the attempts it was allowed: $runs", runs in 5..8)
    }

    @Test fun aRestartBecauseTheServerIgnoredTheRangeCountsAnAttempt() {
        queued(a, f1)
        playable(2)
        files.steps += FakeFiles.partly(1_500)
        drain()
        assertEquals(1, download(f1)!!.attempts)
        files.steps += { c -> c.progress.saved(0, FakeFiles.SIZE, "\"v1\"", c.restarts); FakeFiles.partly(500)(c) } // a 200 to the resume
        drain()
        assertEquals("the restart and the retry", 3, download(f1)!!.attempts)
    }

    @Test fun conversionRestartsCountTowardsTheAttempts() {
        queued(a, f1, quality = DownloadQuality.MEDIUM)
        repeat(3) { convertible() }
        repeat(3) { hls.steps += 1 to FetchResult.RestartConversion }
        assertEquals(QueueRun.Drained, drain(queue(maxAttempts = 3)))
        assertEquals(DownloadState.FAILED to DownloadFailure.SERVER_ERROR, failure(f1))
    }

    @Test fun aRowDeletedMidFetchCancelsAndCleansUp() {
        queued(a, f1, f2)
        playable(2)
        files.steps += { c ->
            c.part.parentFile!!.mkdirs()
            c.part.writeBytes(ByteArray(100))
            runBlocking { store.delete(DownloadKey(a.serverId, f1.id)) } // cancelled from the UI meanwhile
            c.progress.saved(100, FakeFiles.SIZE, "\"v1\"", 0)
            error("the fetch must not go on after its row is gone")
        }
        assertEquals(QueueRun.Drained, drain())
        assertNull(download(f1))
        assertFalse("its files are gone", File(folder(f1), "media.mkv.part").exists())
        assertEquals(DownloadState.COMPLETE, download(f2)!!.state)
    }

    @Test fun artworkFailingStillCompletes() {
        queued(a, f1)
        playable()
        extras.artworkFails = IllegalStateException("artwork")
        drain()
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
    }

    @Test fun anOriginalTheServerNowSaysCannotPlayIsNotPlayable() {
        queued(a, f1)
        playback.answer(fixtureInfo("transcode.json"))
        drain()
        assertEquals(DownloadState.FAILED to DownloadFailure.NOT_PLAYABLE, failure(f1))
        assertTrue("never silently converted", hls.opens.isEmpty() && files.calls.isEmpty())
    }

    @Test fun aStaleRouteGetsANewRouteOnce() {
        queued(a, f1)
        playable()
        files.steps += FakeFiles.answer(FetchResult.Retry(AppError.Kind.OFFLINE))
        assertEquals(QueueRun.Drained, drain())
        assertEquals("a second route", listOf(f1.id, f1.id), routes)
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
    }

    @Test fun aFailedFetchIsThatFailureAndTheQueueMovesOn() {
        queued(a, f1, f2)
        playable(2)
        files.steps += FakeFiles.answer(FetchResult.Failed(DownloadFailure.CHANGED_ON_SERVER))
        assertEquals(QueueRun.Drained, drain())
        assertEquals(DownloadState.FAILED to DownloadFailure.CHANGED_ON_SERVER, failure(f1))
        assertEquals(DownloadState.COMPLETE, download(f2)!!.state)
    }

    @Test fun nothingHereLogsAnIdAPathOrATitle() {
        ShadowLog.clear()
        queued(a, f1, f2)
        playable()
        files.steps += FakeFiles.answer(FetchResult.Unauthorised)
        facts.allow(f1.id)
        verifier.verdicts += Verdict.Bad("signature")
        extras.artworkFails = IllegalStateException(f1.id)
        drain()
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.message}" }
        for (secret in listOf(f1.id, f2.id, f1.name, f2.name, tmp.root.path, "Film")) assertFalse("logged: $secret", secret in logged)
    }
}
