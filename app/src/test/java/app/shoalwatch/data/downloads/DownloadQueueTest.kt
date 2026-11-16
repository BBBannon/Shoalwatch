package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.playback.TrackChoice
import app.shoalwatch.data.playback.TrackPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One item at a time, every step written to Room before the next; conversions resume only inside their own play session. */
class DownloadQueueTest : DownloadQueueTestBase() {
    private val a get() = s1u1
    private val f1 get() = item("f1")
    private val f2 get() = item("f2")
    private val f3 get() = item("f3")

    /** The film's catalogue runtime (80 min) is not the downloaded source's (a 10 s clip, as on the stand-in server). */
    private fun queuedWithItemRuntime(quality: DownloadQuality = DownloadQuality.ORIGINAL) {
        queued(a, f1, quality = quality)
        runBlocking { store.update(key(f1)) { it.copy(runtimeMs = 80 * 60_000L) } }
    }

    private fun withSourceRuntime(name: String, runtimeMs: Long?) =
        app.shoalwatch.data.playback.fixtureInfo(name).let { i -> i.copy(sources = i.sources.map { it.copy(runtimeMs = runtimeMs) }) }

    /** Controller walkthrough W-1: the check compares the file with the runtime of the media source downloaded. */
    @Test fun anOriginalIsCheckedAgainstItsOwnSourcesRuntimeNotTheItems() {
        queuedWithItemRuntime()
        playback.answer(withSourceRuntime("direct-play.json", 10_000))
        assertEquals(QueueRun.Drained, drain())
        assertEquals(listOf<Long?>(10_000), verifier.runtimes)
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
    }

    @Test fun withNoSourceRuntimeTheItemsRuntimeIsUsedAsBefore() {
        queuedWithItemRuntime()
        playback.answer(withSourceRuntime("direct-play.json", null))
        drain()
        assertEquals(listOf<Long?>(80 * 60_000L), verifier.runtimes)
        verifier.runtimes.clear()
        queued(a, f2)
        runBlocking { store.update(key(f2)) { it.copy(runtimeMs = 80 * 60_000L) } }
        playback.answer(withSourceRuntime("direct-play.json", 0)) // a 0 is no runtime
        drain()
        assertEquals(listOf<Long?>(80 * 60_000L), verifier.runtimes)
    }

    @Test fun aConversionChecksItsPlaylistAndFileAgainstTheConvertedSourcesRuntime() {
        queuedWithItemRuntime(DownloadQuality.MEDIUM)
        playback.answer(app.shoalwatch.data.playback.fixtureInfo("direct-play.json"), withSourceRuntime("transcode.json", 10_000))
        assertEquals(QueueRun.Drained, drain())
        assertEquals(listOf<Long?>(10_000), hls.runtimes)
        assertEquals(listOf<Long?>(10_000), verifier.runtimes)
    }

    /**
     * Final review L-5: a removal during a fetch whose result is a retry or a failure (no later write to find the row gone) still
     * leaves no item folder behind: a fetcher may have made it again after the removal deleted it.
     */
    @Test fun aRemovalDuringAFetchThatEndsInARetryOrAFailureLeavesNoFolder() {
        for (result in listOf<FetchResult>(FetchResult.Retry(AppError.Kind.NETWORK), FetchResult.Failed(DownloadFailure.SERVER_ERROR))) {
            queued(a, f1)
            playable()
            files.steps += { c ->
                runBlocking { store.removeOwner(a, f1.id) } // the Downloads tab's Cancel (its files already deleted)
                c.part.parentFile!!.mkdirs(); c.part.writeBytes(ByteArray(10)) // and the fetcher writes on
                result
            }
            drain()
            assertNull(download(f1))
            assertFalse("$result: no folder left", folder(f1).exists())
        }
    }

    /** Final review re-review Nit-1: the folder check after a run is inside the run's guard, so its failure is one attempt. */
    @Test fun aFailureOfTheFolderCheckAfterARunIsOneAttemptNotAnEscape() {
        queued(a, f1)
        playable()
        files.steps += { c ->
            runBlocking { store.removeOwner(a, f1.id) }
            c.part.parentFile!!.mkdirs(); c.part.writeBytes(ByteArray(10))
            fake.allRootsFail = java.io.IOException("read-only")
            FetchResult.Retry(AppError.Kind.NETWORK)
        }
        assertEquals("the drain carries on to the end", QueueRun.Drained, drain())
        assertNull(download(f1))
    }

    @Test fun threeQueuedItemsCompleteInOrder() {
        queued(a, f1, f2, f3)
        playable(3)
        assertEquals(QueueRun.Drained, drain())
        assertEquals(listOf(f1, f2, f3).map { it.id }, files.calls.map { it.itemId })
        for (f in listOf(f1, f2, f3)) {
            val row = download(f)!!
            assertEquals(DownloadState.COMPLETE, row.state)
            assertEquals(FakeVerifier.GOOD, row.sha256)
            assertEquals(clock, row.completedAt)
            assertEquals(clock, row.verifiedAt)
            assertEquals("mkv", row.container)
            assertEquals(FakeFiles.SIZE, row.totalBytes)
            assertEquals("\"v1\"", row.validator)
        }
        assertEquals(listOf("tracks", "segments", "artwork"), extras.calls.take(3))
        assertEquals("the saved copy is checked as an original", false, verifier.verified.first().hls)
        assertTrue(File(folder(f1), "media.mkv").exists())
    }

    @Test fun aPausedItemIsSkippedAndTheNextRuns() {
        queued(a, f1, f2)
        runBlocking { store.setPaused(a, f1.id, true) }
        playable()
        drain()
        assertEquals(listOf(f2.id), files.calls.map { it.itemId })
        assertEquals(DownloadState.PAUSED, download(f1)!!.state)
    }

    @Test fun processDeathMidOriginalRequeuesAndResumesFromTheSavedCount() {
        queued(a, f1)
        playable(2)
        files.steps += FakeFiles.partly(1_500)
        assertEquals(QueueRun.Retry(AppError.Kind.NETWORK), drain())
        runBlocking { store.update(DownloadKey(a.serverId, f1.id)) { it.copy(state = DownloadState.RUNNING) } } // as if the process died mid-fetch
        assertEquals(QueueRun.Drained, drain(queue())) // a new queue over the same database
        val resume = files.calls.last()
        assertEquals(1_500L, resume.resumeFrom)
        assertEquals("\"v1\"", resume.validator)
        assertEquals("the stored total is handed back", FakeFiles.SIZE, resume.storedTotal)
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
    }

    @Test fun processDeathMidConversionAsksAgainAndOpensFromZero() {
        queued(a, f1, quality = DownloadQuality.MEDIUM)
        convertible(); convertible()
        hls.steps += 2 to FetchResult.Retry(AppError.Kind.NETWORK)
        drain()
        assertEquals(2, download(f1)!!.segmentsDone)
        assertEquals(DownloadQuality.MEDIUM, download(f1)!!.quality)
        drain(queue()) // a new process: no live conversion
        assertEquals("asked the server again", 2, hls.opens.size)
        assertEquals("from segment 0, never the old count", listOf(0, 0), hls.fetches.map { it.second })
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
        assertTrue(verifier.verified.last().hls)
    }

    @Test fun aRetryMidConversionInTheSameProcessResumesTheSameSessionWithNoNewPlaybackInfo() {
        queued(a, f1, quality = DownloadQuality.MEDIUM)
        convertible()
        hls.steps += 3 to FetchResult.Retry(AppError.Kind.NETWORK)
        val q = queue()
        assertEquals(QueueRun.Retry(AppError.Kind.NETWORK), drain(q))
        val asked = playback.calls.size
        assertEquals(QueueRun.Drained, drain(q))
        assertEquals(asked, playback.calls.size)
        assertEquals(1, hls.opens.size)
        assertEquals(listOf(0, 3), hls.fetches.map { it.second })
        assertTrue("the same session", hls.fetches[0].first === hls.fetches[1].first)
    }

    @Test fun aLiveConversionOlderThanThirtyMinutesIsReplaced() {
        queued(a, f1, quality = DownloadQuality.MEDIUM)
        convertible(); convertible()
        hls.steps += 3 to FetchResult.Retry(AppError.Kind.NETWORK)
        val q = queue()
        drain(q)
        mono.ms += 30 * 60_000L + 1
        drain(q)
        assertEquals(2, hls.opens.size)
        assertEquals(listOf(0, 0), hls.fetches.map { it.second })
    }

    @Test fun restartConversionStartsAFreshSessionFromZeroAndCountsAnAttempt() {
        queued(a, f1, quality = DownloadQuality.LOW)
        convertible(); convertible()
        hls.steps += 2 to FetchResult.RestartConversion
        assertEquals(QueueRun.Drained, drain())
        assertEquals(2, hls.opens.size)
        assertEquals(listOf(0, 0), hls.fetches.map { it.second })
        assertTrue(hls.fetches[0].first !== hls.fetches[1].first)
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
        assertEquals("attempts reset when it completes", 0, download(f1)!!.attempts)
    }

    @Test fun aShareOnlyOwnerAddNeverReachesTheQueue() {
        queued(a, f1)
        playable()
        drain()
        val before = files.calls.size
        clock += 1 // A is the earlier owner, so A fetches first
        seen(s1u2, f1)
        runBlocking { store.add(s1u2, listOf(shareDownload(f1))) } // another profile adds the copy that is already here
        assertEquals(QueueRun.Drained, drain())
        assertEquals(before, files.calls.size)
        assertEquals(setOf(a.userId, s1u2.userId), runBlocking { store.owners(DownloadKey(a.serverId, f1.id)) }.toSet())
    }

    @Test fun theConversionIsAForcedTranscodeWithTheRememberedAudioLanguagesTrack() {
        val e1 = ep("e1", 1, 1)
        seen(a, series, season1)
        queued(a, e1, quality = DownloadQuality.HIGH)
        runBlocking { TrackPreferences(db.trackChoiceDao()) { 0L }.remember(a, e1.seriesId, TrackChoice("fre", null, true, false, false)) }
        convertible()
        drain()
        val body = playback.bodies.last()
        assertEquals("the remembered French track", 2, body.audioStreamIndex)
        assertFalse("forced: no direct play", body.enableDirectPlay)
        assertFalse(body.enableDirectStream)
        assertEquals(8_000_000, body.maxStreamingBitrate)
        assertEquals("only the conversion's own audio track is saved", 1, extras.tracksAudio.size)
        assertEquals("hls", download(e1)!!.container)
    }

    @Test fun progressStatusesAreEmittedInOrder() {
        queued(a, f1, f2)
        playable(2)
        val statuses = mutableListOf<QueueStatus>()
        drain(statuses = statuses)
        val running = statuses.filterIsInstance<QueueStatus.Running>()
        assertEquals(listOf(1 to 2, 2 to 2), running.map { it.position to it.of }.distinct())
        assertEquals(1f, running.filter { it.position == 1 }.last().progress)
        assertEquals(QueueStatus.Idle, statuses.last())
    }

    @Test fun stoppingBetweenItemsLeavesTheRestQueued() {
        queued(a, f1, f2)
        playable()
        assertEquals(QueueRun.Stopped, drain(stop = { verifier.verified.isNotEmpty() }))
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
        assertEquals(DownloadState.QUEUED, download(f2)!!.state)
    }

    @Test fun stoppingBetweenSegmentsLeavesItQueuedAndTheSameSessionResumes() {
        queued(a, f1, quality = DownloadQuality.MEDIUM)
        convertible()
        val q = queue()
        assertEquals(QueueRun.Stopped, drain(q, stop = { (download(f1)?.segmentsDone ?: 0) >= 2 }))
        assertEquals(DownloadState.QUEUED, download(f1)!!.state)
        assertEquals(2, download(f1)!!.segmentsDone)
        assertEquals(QueueRun.Drained, drain(q))
        assertEquals(listOf(0, 2), hls.fetches.map { it.second })
        assertEquals(1, hls.opens.size)
    }

    /** Tasks 9-10 review I-1: a pause while the item runs stops it at its next step, and the queue never undoes the pause. */
    @Test fun pausingARunningOriginalStopsItAtTheNextSaveAndItStaysPaused() {
        queued(a, f1, f2)
        playable(2)
        files.steps += { c ->
            c.part.parentFile!!.mkdirs()
            c.part.writeBytes(ByteArray(1_000))
            runBlocking { store.setPaused(a, f1.id, true) } // the user presses Pause meanwhile
            c.progress.saved(1_000, FakeFiles.SIZE, "\"v1\"", 0)
            error("the fetch must not go on after a pause")
        }
        assertEquals(QueueRun.Drained, drain())
        assertEquals(DownloadState.PAUSED, download(f1)!!.state)
        assertTrue("the part is kept for a resume", File(folder(f1), "media.mkv.part").exists())
        assertTrue("never checked", verifier.verified.none { it.itemId == f1.id })
        assertEquals("the next item ran", DownloadState.COMPLETE, download(f2)!!.state)
    }

    @Test fun aPauseDuringARetryIsNotUndone() {
        queued(a, f1)
        playable()
        files.steps += { c -> runBlocking { store.setPaused(a, f1.id, true) }; FetchResult.Retry(AppError.Kind.NETWORK) }
        drain()
        assertEquals(DownloadState.PAUSED, download(f1)!!.state)
    }

    @Test fun aPausedConversionKeepsItsSessionAndResumesWhereItWas() {
        queued(a, f1, quality = DownloadQuality.MEDIUM)
        convertible()
        hls.onSegment = { n -> if (n == 2) runBlocking { store.setPaused(a, f1.id, true) } }
        val q = queue()
        assertEquals(QueueRun.Drained, drain(q))
        assertEquals(DownloadState.PAUSED, download(f1)!!.state)
        assertEquals(2, download(f1)!!.segmentsDone)
        hls.onSegment = {}
        runBlocking { store.setPaused(a, f1.id, false) }
        assertEquals(QueueRun.Drained, drain(q))
        assertEquals("the same session, where it was", listOf(0, 2), hls.fetches.map { it.second })
        assertEquals(1, hls.opens.size)
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
    }

    /** Tasks 11-12 review I-4: a remembered conversion belongs to its own row (its request), never to a new row of the same item. */
    @Test fun aConversionRemovedAndAddedAgainAtAnotherQualityUsesANewConversionAndEndsWithItsOwnQuality() {
        queued(a, f1, quality = DownloadQuality.MEDIUM)
        convertible()
        hls.onSegment = { n -> if (n == 2) runBlocking { store.setPaused(a, f1.id, true) } }
        val q = queue()
        drain(q)
        assertEquals(DownloadState.PAUSED, download(f1)!!.state)
        hls.onSegment = {}
        runBlocking { store.removeOwner(a, f1.id) }
        assertNull(download(f1))
        clock += 60_000 // a minute later: well inside the 30 minutes a conversion is remembered
        queued(a, f1, quality = DownloadQuality.LOW)
        convertible()
        assertEquals(QueueRun.Drained, drain(q))
        assertEquals("a new conversion", 2, hls.opens.size)
        assertEquals(app.shoalwatch.data.playback.Quality.MBPS_1_5, hls.opens[1].plan.request.quality)
        val row = download(f1)!!
        assertEquals(DownloadState.COMPLETE, row.state)
        assertEquals(DownloadQuality.LOW, row.quality)
        assertEquals("hls", row.container)
        assertNotNull(row.mediaSourceId)
    }

    @Test fun aConversionOpenedUnderOneProfileIsNeverReusedUnderAnothers() {
        queued(a, f1, quality = DownloadQuality.MEDIUM)
        clock += 1 // A is the earlier owner, so A fetches first
        seen(s1u2, f1)
        runBlocking { store.add(s1u2, listOf(shareDownload(f1))) } // B shares the same row: same request, same quality
        convertible()
        hls.onSegment = { n -> if (n == 2) runBlocking { store.setPaused(a, f1.id, true) } }
        val q = queue()
        drain(q)
        assertEquals(DownloadState.PAUSED, download(f1)!!.state)
        assertEquals("A opened it", 1, hls.opens.size)
        hls.onSegment = {}
        runBlocking { store.removeOwner(a, f1.id) }
        assertNotNull("the row stays for B", download(f1))
        runBlocking { store.setPaused(s1u2, f1.id, false) }
        clock += 60_000 // well inside the 30 minutes a conversion is remembered
        convertible()
        assertEquals(QueueRun.Drained, drain(q))
        assertEquals("B's drain opens its own conversion, under its own sign-in", 2, hls.opens.size)
        assertEquals("from the first segment", 0, hls.fetches.last().second)
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
    }

    /** Tasks 1-4 review Minor 4 and Tasks 5-8 review Minor 10: no download model prints an id, a size or a validator. */
    @Test fun downloadModelsNeverPrintIdsSizesOrValidators() {
        queued(a, f1)
        runBlocking { store.update(key(f1)) { it.copy(totalBytes = 123_456_789, validator = "\"secret-etag\"") } }
        val printed = listOf(
            download(f1).toString(),
            app.shoalwatch.data.downloads.api.DownloadFacts(f1.id, true, "src1", "mkv", 123_456_789, "\"secret-etag\"").toString(),
            PlannedItem(f1.id, DownloadQuality.ORIGINAL, 123_456_789, shareOnly = false).toString(),
            Skipped(f1.id, SkipReason.NOT_ALLOWED).toString(),
            OriginalCheck.Playable("src1", "mkv", 123_456_789, "\"secret-etag\"", emptyList(), emptyList(), null).toString(),
        )
        for (p in printed) {
            assertFalse(p, p.contains(f1.id) || p.contains("123456789") || p.contains("secret-etag") || p.contains("src1"))
        }
    }

    @Test fun aRowWithNoContainerOrMediaSourceIsNeverMarkedComplete() {
        queued(a, f1)
        playable()
        val q = queue()
        files.steps += { c ->
            val done = FakeFiles.whole()(c)
            runBlocking { store.update(DownloadKey(a.serverId, f1.id)) { it.copy(mediaSourceId = null) } } // the record lost its source
            done
        }
        drain(q)
        assertNotEquals(DownloadState.COMPLETE, download(f1)!!.state)
    }

    @Test fun recheckMarksADamagedCopy() {
        queued(a, f1)
        playable()
        drain()
        verifier.recheckAnswer = true
        runBlocking { queue().recheck(DownloadKey(a.serverId, f1.id)) }
        assertEquals(DownloadState.COMPLETE, download(f1)!!.state)
        verifier.recheckAnswer = false
        runBlocking { queue().recheck(DownloadKey(a.serverId, f1.id)) }
        assertEquals(DownloadState.FAILED, download(f1)!!.state)
        assertEquals(DownloadFailure.DAMAGED, download(f1)!!.failure)
        runBlocking { queue().recheck(DownloadKey(a.serverId, item("nothing").id)) } // no row: nothing happens
    }
}
