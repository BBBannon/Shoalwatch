package app.shoalwatch.data.downloads

import app.shoalwatch.data.api.MediaRoute
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.playback.AudioTrack
import app.shoalwatch.data.playback.Resolved
import app.shoalwatch.data.playback.SubtitleTrack
import app.shoalwatch.data.servers.Session
import java.io.File

/*
 * The parts the queue drives, as seams so `DownloadQueueTest` can script them (the plan's "fakes for the fetchers, verifier,
 * gateway and resolver"). Each is implemented by exactly one production class, whose KDoc holds the rules.
 */

/** [FileFetcher]. */
internal interface OriginalFetch {
    suspend fun fetch(
        route: MediaRoute, itemId: String, part: File, target: File, resumeFrom: Long, validator: String?,
        expectedBytes: Long?, storedTotal: Long?, restarts: Int, fits: (Long) -> Boolean, progress: Progress,
    ): FetchResult
}

/** [HlsFetcher]. */
internal interface ConversionFetch {
    suspend fun open(resolved: Resolved, folder: File, runtimeMs: Long?, space: ConversionSpace): Pair<LiveConversion?, FetchResult>
    suspend fun fetch(live: LiveConversion, folder: File, fromSegment: Int, progress: suspend (done: Int, total: Int) -> Unit): FetchResult
}

/** [ExtrasFetcher]: best effort, never throws for a server's answer. */
internal interface DownloadExtras {
    suspend fun tracks(s: Session, key: DownloadKey, folder: File, mediaSourceId: String, audio: List<AudioTrack>, subtitles: List<SubtitleTrack>, quality: DownloadQuality)
    suspend fun segments(s: Session, key: DownloadKey)
    suspend fun artwork(s: Session, itemIds: List<String>)
}

/** [DownloadVerifier]. */
internal interface PlayableCheck {
    suspend fun verify(media: SavedMedia, expectedBytes: Long?, runtimeMs: Long?, hlsShape: List<Long>?, audioListed: Boolean = true): Verdict
    suspend fun recheck(media: SavedMedia, sha256: String): Boolean
}
