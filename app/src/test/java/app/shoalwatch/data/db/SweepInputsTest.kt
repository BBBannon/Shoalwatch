package app.shoalwatch.data.db

import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.downloads.ArtworkKey
import app.shoalwatch.data.downloads.ArtworkSlot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What the orphan sweep may work from: one consistent read, and nothing at all while a download is being written or checked. */
@RunWith(RobolectricTestRunner::class)
class SweepInputsTest : DownloadTestBase() {
    private val raw get() = db.openHelper.writableDatabase

    @Test fun whenNothingIsRunningTheSweepGetsEveryKeyServerAndPinnedPicture() {
        DownloadRows.download(raw, S1, id("a"), state = "COMPLETE", position = 1)
        DownloadRows.download(raw, S2, id("b"), state = "QUEUED", position = 2)
        DownloadRows.artwork(raw, S1, id("a"))
        val inputs = runBlocking { store.sweepInputs() }!!
        assertEquals(setOf(DownloadKey(S1, id("a")), DownloadKey(S2, id("b"))), inputs.known)
        assertEquals(setOf(S1, S2), inputs.servers)
        assertEquals(setOf(ArtworkKey(S1, id("a"), ArtworkSlot.PRIMARY, "tag1")), inputs.artwork)
    }

    @Test fun aRunningOrVerifyingDownloadMeansNoSweep() {
        DownloadRows.download(raw, S1, id("a"), state = "RUNNING", position = 1)
        assertNull(runBlocking { store.sweepInputs() })
        sql("UPDATE downloads SET state = 'VERIFYING'")
        assertNull(runBlocking { store.sweepInputs() })
        sql("UPDATE downloads SET state = 'PAUSED'")
        assertEquals(1, runBlocking { store.sweepInputs() }!!.known.size)
    }

    @Test fun aPinnedPictureOfAKindThisVersionDoesNotKnowIsLeftOutNotACrash() {
        DownloadRows.download(raw, S1, id("a"), state = "COMPLETE")
        sql("INSERT INTO pinned_artwork (serverId, itemId, slot, tag, bytes, savedAt) VALUES (?, ?, 'LOGO', 'tag9', 1, 1)", S1, id("a"))
        DownloadRows.artwork(raw, S1, id("a"))
        assertEquals(setOf(ArtworkKey(S1, id("a"), ArtworkSlot.PRIMARY, "tag1")), runBlocking { store.sweepInputs() }!!.artwork)
    }
}
