package app.shoalwatch.data.db

import app.shoalwatch.S1
import app.shoalwatch.data.db.downloads.DownloadSegmentEntity
import app.shoalwatch.data.db.downloads.DownloadTrackEntity
import app.shoalwatch.data.downloads.ArtworkSlot
import app.shoalwatch.data.downloads.TrackType
import app.shoalwatch.data.playback.SegmentKind
import app.shoalwatch.data.playback.SubtitleKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The extras of a download, read back: a kind this version does not know (a later version wrote it) drops that row, never the read. */
class ExtrasRowsTest : DownloadTestBase() {
    private val raw get() = db.openHelper.writableDatabase
    private val dao get() = db.downloadDao()
    private val item get() = id("film")

    private fun track(type: TrackType, index: Int, kind: SubtitleKind? = null, saved: Boolean = false) =
        DownloadTrackEntity(S1, item, type, index, "Label $index", "eng", "aac", kind, if (kind != null) "srt" else null, false, false, false, false, 2, saved)

    @Test fun tracksAreReplacedAndReadBackInOrder() = runBlocking {
        DownloadRows.download(raw, S1, item)
        dao.replaceTracks(S1, item, listOf(track(TrackType.SUBTITLE, 3, SubtitleKind.TEXT, saved = true), track(TrackType.AUDIO, 1)))
        dao.replaceTracks(S1, item, listOf(track(TrackType.AUDIO, 1), track(TrackType.SUBTITLE, 4, SubtitleKind.STYLED_TEXT)))
        assertEquals(listOf(track(TrackType.AUDIO, 1), track(TrackType.SUBTITLE, 4, SubtitleKind.STYLED_TEXT)), dao.tracks(S1, item))
    }

    @Test fun aTrackOfAnUnknownTypeOrSubtitleKindIsDropped() = runBlocking {
        DownloadRows.download(raw, S1, item)
        dao.replaceTracks(S1, item, listOf(track(TrackType.AUDIO, 1)))
        sql("INSERT INTO download_tracks (serverId, itemId, type, streamIndex, label, fileSaved, forced, hearingImpaired, isDefault, external) VALUES (?, ?, 'VIDEO', 0, 'x', 0, 0, 0, 0, 0)", S1, item)
        sql("INSERT INTO download_tracks (serverId, itemId, type, streamIndex, label, kind, fileSaved, forced, hearingImpaired, isDefault, external) VALUES (?, ?, 'SUBTITLE', 9, 'x', 'HOLOGRAM', 0, 0, 0, 0, 0)", S1, item)
        assertEquals(listOf(track(TrackType.AUDIO, 1)), dao.tracks(S1, item))
    }

    @Test fun segmentsAreReplacedAndAnUnknownKindIsDropped() = runBlocking {
        DownloadRows.download(raw, S1, item)
        dao.replaceSegments(S1, item, listOf(DownloadSegmentEntity(S1, item, SegmentKind.OUTRO, 9_000, 10_000), DownloadSegmentEntity(S1, item, SegmentKind.INTRO, 1_000, 3_000)))
        sql("INSERT INTO download_segments (serverId, itemId, kind, startMs, endMs) VALUES (?, ?, 'SPONSOR', 5000, 6000)", S1, item)
        assertEquals(listOf(SegmentKind.INTRO, SegmentKind.OUTRO), dao.segments(S1, item).map { it.kind })
    }

    @Test fun pinnedPicturesAreInsertedThenUpdatedNeverReplacedAndAnUnknownSlotIsDropped() = runBlocking {
        dao.pinArtwork(S1, item, ArtworkSlot.PRIMARY, "old", 10, 1)
        dao.pinArtwork(S1, item, ArtworkSlot.PRIMARY, "new", 20, 2)
        sql("INSERT INTO pinned_artwork (serverId, itemId, slot, tag, bytes, savedAt) VALUES (?, ?, 'LOGO', 'x', 1, 1)", S1, item)
        assertEquals(mapOf(ArtworkSlot.PRIMARY to "new"), dao.pinnedArtwork(S1, item))
        dao.unpinArtwork(S1, item, ArtworkSlot.PRIMARY)
        assertEquals(emptyMap<ArtworkSlot, String>(), dao.pinnedArtwork(S1, item))
    }
}
