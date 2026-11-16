package app.shoalwatch.data.downloads

import app.shoalwatch.S1
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

    /** Final review M-2: the fetcher is given the queue's own space rule (free space above the reserve), for a size only the response states. */
    @Test fun theFileFetcherIsGivenTheQueuesSpaceRule() {
        queued(a, f1)
        playable()
        fake.free[null] = QueueSpace.INTERNAL_RESERVE + (2L shl 30) // the fixture's Size (1.2 GB) fits
        var rule: ((Long) -> Boolean)? = null
        files.steps += { c -> rule = c.fits; FetchResult.Failed(DownloadFailure.NO_SPACE) }
        drain()
        assertTrue(rule!!(2L shl 30))
        assertFalse("not one byte into the reserve", rule!!((2L shl 30) + 1))
        assertEquals(DownloadState.FAILED to DownloadFailure.NO_SPACE, failure(f1))
    }

    /**
     * Tasks 9-10 review I-2 (a). C (U2) queued the original with its CanDownload unknown; P (U1) then shared it with a confirmed
     * yes. C's sign-in is gone, so P fetches: C must not keep a copy fetched under P's permission.
     */
    @Test fun anUnconfirmedOwnerDoesNotKeepACopyFetchedUnderAnotherProfilesPermission() {
        val c = s1u2
        queued(c, f1) // newDownload: not confirmed
        seen(a, f1)
        runBlocking { store.add(a, listOf(shareDownload(f1))) }
        tokens -= c
        playable()
        assertEquals(QueueRun.Drained, drain())
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
        assertEquals(listOf(a.userId), runBlocking { store.owners(DownloadKey(S1, f1.id)) })
    }

    /** The converse: C fetches first and the server refuses C; P, whose permission was confirmed, then fetches it for P alone. */
    @Test fun aRefusedOwnerIsDroppedAndTheNextOwnerFetchesUnderItsOwnPermission() {
        val c = s1u2
        queued(c, f1)
        seen(a, f1)
        clock += 10
        runBlocking { store.add(a, listOf(shareDownload(f1))) }
        playable(2)
        files.steps += FakeFiles.answer(FetchResult.Failed(DownloadFailure.NOT_ALLOWED))
        assertEquals(QueueRun.Drained, drain())
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
        assertEquals(listOf(a.userId), runBlocking { store.owners(DownloadKey(S1, f1.id)) })
        assertEquals(2, files.calls.size)
    }

    @Test fun aRefusalOfTheLastOwnerRemovesItsPermissionAtOnce() {
        queued(a, f1)
        runBlocking { store.confirm(DownloadKey(S1, f1.id), a.userId) }
        playable()
        files.steps += FakeFiles.answer(FetchResult.Failed(DownloadFailure.NOT_ALLOWED))
        drain()
        assertEquals(DownloadFailure.NOT_ALLOWED, download(f1)!!.failure)
        assertFalse(runBlocking { store.isConfirmed(DownloadKey(S1, f1.id), a.userId) })
    }

    @Test fun anOriginalServedToTheOwnersOwnSignInConfirmsItsPermission() {
        val c = s1u2
        queued(c, f1)
        playable()
        drain()
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
        assertTrue(runBlocking { store.isConfirmed(DownloadKey(S1, f1.id), c.userId) })
    }

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

    /**
     * Tasks 11-12 review Minor 2: a file renamed before the process died, found by an owner whose own permission was never
     * confirmed (another owner fetched it under its sign-in), is fetched again under this owner's sign-in, never kept as it is.
     */
    @Test fun aFileLeftByAnotherOwnersFetchIsFetchedAgainUnderTheFetchersOwnSignIn() {
        queued(a, f1) // CanDownload unknown: A's permission is not confirmed
        clock += 10
        seen(s1u2, f1)
        runBlocking { store.add(s1u2, listOf(shareDownload(f1))) } // B, confirmed, fetched it and died just after the rename
        paths.mediaFile(folder(f1).also { it.mkdirs() }, "mkv").writeBytes(ByteArray(FakeFiles.SIZE.toInt()))
        runBlocking { store.update(key(f1)) { it.copy(doneBytes = FakeFiles.SIZE, totalBytes = FakeFiles.SIZE, validator = "\"v1\"") } }
        playable()
        assertEquals(QueueRun.Drained, drain())
        assertEquals("A's own /Download was asked, from the start", listOf(0L), files.calls.map { it.resumeFrom })
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
        assertTrue("and A's permission is now its own", runBlocking { store.isConfirmed(key(f1), a.userId) })
    }

    @Test fun aFileRenamedByTheFetchersOwnSignInIsOnlyChecked() {
        queued(a, f1)
        runBlocking { store.markFetcher(key(f1), a.userId) } // A's own sign-in fetched it, then the process died
        paths.mediaFile(folder(f1).also { it.mkdirs() }, "mkv").writeBytes(ByteArray(FakeFiles.SIZE.toInt()))
        runBlocking { store.update(key(f1)) { it.copy(doneBytes = FakeFiles.SIZE, totalBytes = FakeFiles.SIZE) } }
        playable()
        drain()
        assertTrue("nothing fetched again", files.calls.isEmpty())
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
    }

    /** Final review L-7: a confirmed permission is not enough; the bytes must have come through this sign-in. */
    @Test fun aFileRenamedUnderAnotherOwnersSignInIsFetchedAgainEvenWhenTheFetcherIsConfirmed() {
        queued(a, f1)
        runBlocking { store.confirm(key(f1), a.userId) } // the planner's facts said yes for A
        clock += 10
        seen(s1u2, f1)
        runBlocking { store.add(s1u2, listOf(shareDownload(f1))); store.markFetcher(key(f1), s1u2.userId) } // but B's sign-in fetched it
        paths.mediaFile(folder(f1).also { it.mkdirs() }, "mkv").writeBytes(ByteArray(FakeFiles.SIZE.toInt()))
        runBlocking { store.update(key(f1)) { it.copy(doneBytes = FakeFiles.SIZE, totalBytes = FakeFiles.SIZE, validator = "\"v1\"") } }
        playable()
        assertEquals(QueueRun.Drained, drain())
        assertEquals("A's own /Download, from the start", listOf(0L), files.calls.map { it.resumeFrom })
        assertTrue("A is now the one whose sign-in fetched it", runBlocking { store.isFetcher(key(f1), a.userId) && !store.isFetcher(key(f1), s1u2.userId) })
    }

    /** Final review L-7: a part begun under one owner's sign-in is never finished with another's (Range and the first's validator). */
    @Test fun aPartBegunUnderAnotherOwnersSignInStartsAgainFromZero() {
        queued(a, f1)
        clock += 10
        seen(s1u2, f1)
        runBlocking { store.add(s1u2, listOf(shareDownload(f1))) }
        playable(2)
        files.steps += FakeFiles.partly(1_000)
        assertEquals(QueueRun.Retry(AppError.Kind.NETWORK), drain())
        assertEquals(1_000L, download(f1)!!.doneBytes)
        tokens -= a // A signed out: B fetches now
        assertEquals(QueueRun.Drained, drain())
        assertEquals("B starts again from zero, with no validator of A's", listOf(0L, 0L), files.calls.map { it.resumeFrom })
        assertNull(files.calls.last().validator)
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
    }

    @Test fun theEarliestOwnerWithATokenFetches() {
        queued(a, f1)
        clock += 10
        seen(s1u2, f1)
        runBlocking { store.add(s1u2, listOf(shareDownload(f1))) }
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

    /** Tasks 9-10 review Minor 5: a card pulled mid-download is STORAGE_MISSING, never "no space" or a network retry. */
    @Test fun aCardPulledMidDownloadIsStorageMissingNotNoSpaceOrANetworkRetry() {
        val card = tmp.newFolder("card")
        fake.card("CARD-1", card)
        fake.free["CARD-1"] = 1L shl 40
        fun onCard(item: app.shoalwatch.data.library.Item) = newDownload(item).let { n ->
            app.shoalwatch.data.db.downloads.DownloadStore.NewDownload(n.itemId, n.kind, n.seriesId, n.seasonId, n.quality, StorageKind.REMOVABLE, "CARD-1", n.runtimeMs, null)
        }
        seen(a, f1, f2)
        runBlocking { store.add(a, listOf(onCard(f1), onCard(f2))) }
        playable(2)
        files.steps += { fake.unmount("CARD-1"); FetchResult.Failed(DownloadFailure.NO_SPACE) }
        files.steps += { FetchResult.Retry(AppError.Kind.NETWORK) }
        drain()
        assertEquals(DownloadState.FAILED to DownloadFailure.STORAGE_MISSING, failure(f1))
        assertEquals(DownloadState.FAILED to DownloadFailure.STORAGE_MISSING, failure(f2))
        assertEquals("no attempt used", 0, download(f2)!!.attempts)
    }

    /** Tasks 9-10 review, "cannot verify": a media source id from the server is validated before anything of it reaches Room. */
    @Test fun aHostileMediaSourceIdIsNeverWrittenToRoom() {
        queued(a, f1, quality = DownloadQuality.MEDIUM)
        val hostile = fixtureInfo("transcode.json").let { info -> info.copy(sources = info.sources.map { it.copy(id = "../../System") }) }
        playback.answer(fixtureInfo("direct-play.json"), hostile)
        drain()
        assertEquals(DownloadState.FAILED to DownloadFailure.NOT_PLAYABLE, failure(f1))
        assertNull(download(f1)!!.mediaSourceId)
        assertTrue(hls.opens.isEmpty())
    }

    /** Tasks 9-10 review Minor 9: a file saved under an older container (the server remuxed it) does not stay beside the new one. */
    @Test fun aMediaFileOfAnotherContainerIsDeletedOnceTheContainerIsKnown() {
        queued(a, f1)
        playable()
        val old = File(folder(f1).apply { mkdirs() }, "media.mp4").apply { writeBytes(ByteArray(5)) }
        val oldPart = File(folder(f1), "media.mp4.part").apply { writeBytes(ByteArray(5)) }
        drain()
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
        assertTrue(File(folder(f1), "media.mkv").exists())
        assertFalse(old.exists() || oldPart.exists())
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

    /** Tasks 9-10 review I-3: an unexpected exception is an attempt, never a row left RUNNING that blocks the queue. */
    @Test fun anUnexpectedExceptionIsAnAttemptAndEightAreServerErrorThenTheNextItemRuns() {
        queued(a, f1, f2)
        playable(9)
        repeat(8) { files.steps += { throw java.io.IOException("read-only file system") } }
        assertEquals(QueueRun.Retry(AppError.Kind.UNKNOWN), drain())
        assertEquals(DownloadState.QUEUED, download(f1)!!.state)
        assertEquals(1, download(f1)!!.attempts)
        var runs = 1
        while (download(f1)!!.state != DownloadState.FAILED && runs++ < 12) drain()
        assertEquals(DownloadState.FAILED to DownloadFailure.SERVER_ERROR, failure(f1))
        assertEquals(DownloadState.COMPLETE, download(f2)!!.state)
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
