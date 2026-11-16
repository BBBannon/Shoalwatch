package app.shoalwatch.data.db

import app.shoalwatch.data.db.downloads.Converters
import app.shoalwatch.data.downloads.AfterFinish
import app.shoalwatch.data.downloads.ArtworkSlot
import app.shoalwatch.data.downloads.DownloadFailure
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.SharedDelete
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.downloads.TrackType
import app.shoalwatch.data.playback.Quality
import app.shoalwatch.data.playback.SegmentKind
import app.shoalwatch.data.playback.SubtitleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Enums are stored by name; a name a later version wrote reads as a safe value, or (for the extras) as null so the row is dropped. */
class DownloadConvertersTest {
    private val c = Converters()

    @Test fun everyKnownNameRoundTrips() {
        DownloadQuality.entries.forEach { assertEquals(it, c.toQuality(c.fromQuality(it))) }
        DownloadState.entries.forEach { assertEquals(it, c.toState(c.fromState(it))) }
        DownloadFailure.entries.forEach { assertEquals(it, c.toFailure(c.fromFailure(it))) }
        StorageKind.entries.forEach { assertEquals(it, c.toStorage(c.fromStorage(it))) }
        ArtworkSlot.entries.forEach { assertEquals(it, c.toSlot(c.fromSlot(it))) }
        TrackType.entries.forEach { assertEquals(it, c.toTrackType(c.fromTrackType(it))) }
        SubtitleKind.entries.forEach { assertEquals(it, c.toSubtitleKind(c.fromSubtitleKind(it))) }
        SegmentKind.entries.forEach { assertEquals(it, c.toSegmentKind(c.fromSegmentKind(it))) }
        AfterFinish.entries.forEach { assertEquals(it, c.toAfterFinish(c.fromAfterFinish(it))) }
        SharedDelete.entries.forEach { assertEquals(it, c.toSharedDelete(c.fromSharedDelete(it))) }
        assertEquals("COMPLETE", c.fromState(DownloadState.COMPLETE))
    }

    @Test fun anUnknownNameReadsAsTheSafeValue() {
        assertEquals(DownloadState.FAILED, c.toState("ARCHIVED"))
        assertEquals(DownloadFailure.UNKNOWN, c.toFailure("QUOTA"))
        assertEquals(DownloadQuality.ORIGINAL, c.toQuality("ULTRA"))
        assertEquals(StorageKind.INTERNAL, c.toStorage("CLOUD"))
        assertEquals(AfterFinish.ASK, c.toAfterFinish("SOMETIMES"))
        assertEquals(SharedDelete.ANYONE, c.toSharedDelete("OWNER"))
    }

    @Test fun anUnknownExtraKindReadsAsNullSoTheMapperDropsTheRow() {
        assertNull(c.toSlot("LOGO"))
        assertNull(c.toTrackType("VIDEO"))
        assertNull(c.toSubtitleKind("BRAILLE"))
        assertNull(c.toSegmentKind("CREDITS"))
    }

    @Test fun nullStaysNull() {
        assertNull(c.fromFailure(null))
        assertNull(c.toFailure(null))
        assertNull(c.fromSubtitleKind(null))
        assertNull(c.toSubtitleKind(null))
    }

    @Test fun qualitiesAreMilestone3sStreamingSteps() {
        assertEquals(listOf(null, Quality.MBPS_8, Quality.MBPS_4, Quality.MBPS_1_5), DownloadQuality.entries.map { it.quality })
    }
}
