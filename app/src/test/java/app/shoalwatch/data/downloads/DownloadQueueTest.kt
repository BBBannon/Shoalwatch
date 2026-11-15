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
