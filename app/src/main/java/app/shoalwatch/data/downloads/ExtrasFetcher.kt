package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.db.downloads.DownloadDao
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.DownloadSegmentEntity
import app.shoalwatch.data.db.downloads.DownloadTrackEntity
import app.shoalwatch.data.db.library.ItemDao
import app.shoalwatch.data.db.library.toModel
import app.shoalwatch.data.images.ArtworkRejectedException
import app.shoalwatch.data.images.ArtworkRequest
import app.shoalwatch.data.images.ArtworkType
import app.shoalwatch.data.images.PinnedArtwork
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.playback.AudioTrack
import app.shoalwatch.data.playback.SubtitleKind
import app.shoalwatch.data.playback.SubtitleTrack
import app.shoalwatch.data.playback.api.PlaybackGateway
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * What a download keeps besides its media: the track list with subtitle files, the skip markers and pinned pictures (see
 * "Download extras" in docs/ARCHITECTURE.md). Every part is best effort: a failure leaves that part out (a subtitle says "Not
 * saved with this download") and never fails the download. Subtitle bytes are the server's, saved as they came (the gateway caps
 * them at 8 MiB and checks the type) and read later only by the player's own parsers (`SubtitleFiles`).
 */
internal class ExtrasFetcher(
    private val gateway: PlaybackGateway,
    private val api: ServerApi,
    private val items: ItemDao,
    private val downloads: DownloadDao,
    private val paths: DownloadPaths,
    private val pinned: PinnedArtwork,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxSubtitles: Int = 30,
) : DownloadExtras {
    /**
     * Saves the track list: every audio track of an ORIGINAL (a conversion keeps one, the one it was made with: the caller passes
     * it first); every subtitle track up to [maxSubtitles], with TEXT and STYLED_TEXT files saved as `sub-<index>.<fileFormat>`;
     * PICTURE tracks only for ORIGINAL (in the stream, no file; the server's HLS carries none, and a burn-in would fix one choice
     * for ever). Each file goes to a `.part`, is fsync'd and renamed. Replaces any earlier list for [key].
     */
    override suspend fun tracks(
        s: Session, key: DownloadKey, folder: File, mediaSourceId: String, audio: List<AudioTrack>, subtitles: List<SubtitleTrack>, quality: DownloadQuality,
    ) {
        val original = quality == DownloadQuality.ORIGINAL
        val audioRows = (if (original) audio else audio.take(1)).map { a ->
            DownloadTrackEntity(key.serverId, key.itemId, TrackType.AUDIO, a.index, a.label, a.language, a.codec, null, null,
                false, false, a.isDefault, false, a.channels, fileSaved = false)
        }
        val subtitleRows = subtitles.filter { original || it.kind != SubtitleKind.PICTURE }.take(maxSubtitles).map { t ->
            val saved = t.kind != SubtitleKind.PICTURE && t.fileFormat != null && saveSubtitle(s, key, folder, mediaSourceId, t)
            DownloadTrackEntity(key.serverId, key.itemId, TrackType.SUBTITLE, t.index, t.label, t.language, t.format, t.kind, t.fileFormat,
                t.forced, t.hearingImpaired, t.isDefault, t.external, null, fileSaved = saved)
        }
        downloads.replaceTracks(key.serverId, key.itemId, (audioRows + subtitleRows).distinctBy { it.type to it.streamIndex })
    }

    /** True when the file is on disk under its own name. */
    private suspend fun saveSubtitle(s: Session, key: DownloadKey, folder: File, mediaSourceId: String, t: SubtitleTrack): Boolean {
        val format = t.fileFormat ?: return false
        return try {
            val part = paths.subtitlePartFile(folder, t.index, format)
            val target = paths.subtitleFile(folder, t.index, format)
            val bytes = gateway.subtitleFile(s, key.itemId, mediaSourceId, t.index, format)
            withContext(io) {
                FileOutputStream(part).use { out -> out.write(bytes); out.fd.sync() }
                FetchSupport.rename(part, target)
            }
            true
        } catch (e: AppError) {
            AppLog.d(TAG, "subtitle not saved: ${e.kind}")
            false
        } catch (e: IOException) {
            AppLog.d(TAG, "subtitle not saved: ${e.javaClass.simpleName}")
            false
        } catch (e: IllegalArgumentException) {
            false // an index or format DownloadPaths will not name
        }
    }

    /** The skip markers (at most 200, as the gateway already bounds them). Best effort: on failure the saved ones stay as they were. */
    override suspend fun segments(s: Session, key: DownloadKey) {
        val found = try {
            gateway.segments(s, key.itemId)
        } catch (e: AppError) {
            AppLog.d(TAG, "segments not saved: ${e.kind}")
            return
        }
        downloads.replaceSegments(key.serverId, key.itemId, found.map { DownloadSegmentEntity(key.serverId, key.itemId, it.kind, it.startMs, it.endMs) })
    }

    /**
     * PRIMARY, BACKDROP and THUMB of each item, its season and its series, where a tag exists and the session was shown that item:
     * 720 px wide (BACKDROP 1280), through the loader's own request and checks, kept on INTERNAL storage only. A picture already
     * pinned with the same tag is skipped, so a season's pictures are fetched once for all its episodes.
     */
    override suspend fun artwork(s: Session, itemIds: List<String>) {
        val targets = LinkedHashMap<String, Item>()
        for (id in itemIds.distinct()) {
            val item = visible(s, id) ?: continue
            for (related in listOfNotNull(item.id, item.seasonId, item.seriesId)) {
                if (related !in targets) visible(s, related)?.let { targets[related] = it }
            }
        }
        for (item in targets.values) for ((slot, tag) in tagsOf(item)) pin(s, item.id, slot, tag)
    }

    private suspend fun visible(s: Session, itemId: String): Item? = items.getItem(s.serverId, s.userId, itemId)?.toModel()?.item

    private fun tagsOf(item: Item): List<Pair<ArtworkSlot, String>> = listOfNotNull(
        item.images.primaryTag?.let { ArtworkSlot.PRIMARY to it },
        item.images.backdropTag?.let { ArtworkSlot.BACKDROP to it },
        item.images.thumbTag?.let { ArtworkSlot.THUMB to it },
    )

    private suspend fun pin(s: Session, itemId: String, slot: ArtworkSlot, tag: String) {
        if (pinned.has(s.serverId, itemId, slot, tag)) return
        val type = when (slot) { ArtworkSlot.PRIMARY -> ArtworkType.PRIMARY; ArtworkSlot.BACKDROP -> ArtworkType.BACKDROP; ArtworkSlot.THUMB -> ArtworkType.THUMB }
        val width = if (slot == ArtworkSlot.BACKDROP) BACKDROP_WIDTH else WIDTH
        try {
            val picture = ArtworkRequest.download(api, s, itemId, type, tag, width)
            pinned.save(s.serverId, itemId, slot, tag, picture.bytes)
        } catch (e: ArtworkRejectedException) {
            AppLog.d(TAG, "artwork not pinned: ${e.reason}")
        } catch (e: AppError) {
            AppLog.d(TAG, "artwork not pinned: ${e.kind}")
        } catch (e: IOException) {
            AppLog.d(TAG, "artwork not pinned: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            AppLog.d(TAG, "artwork not pinned: name") // an id or tag DownloadPaths will not name
        } catch (e: IllegalStateException) {
            AppLog.d(TAG, "artwork not pinned: storage") // internal storage not available
        }
    }

    private companion object {
        const val TAG = "Downloads"
        const val WIDTH = 720
        const val BACKDROP_WIDTH = 1280
    }
}
