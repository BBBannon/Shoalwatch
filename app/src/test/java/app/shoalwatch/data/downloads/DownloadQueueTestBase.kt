package app.shoalwatch.data.downloads

import app.shoalwatch.TestMonotonic
import app.shoalwatch.data.api.MediaRoute
import app.shoalwatch.data.db.DownloadTestBase
import app.shoalwatch.data.db.downloads.DownloadEntity
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.playback.AndroidDeviceCapabilities
import app.shoalwatch.data.playback.AudioTrack
import app.shoalwatch.data.playback.CaptionDefaults
import app.shoalwatch.data.playback.CodecSupport
import app.shoalwatch.data.playback.FakePlaybackGateway
import app.shoalwatch.data.playback.PlaybackFakes
import app.shoalwatch.data.playback.PlaybackResolver
import app.shoalwatch.data.playback.Resolved
import app.shoalwatch.data.playback.SubtitleTrack
import app.shoalwatch.data.playback.TrackPreferences
import app.shoalwatch.data.playback.fixtureInfo
import app.shoalwatch.data.servers.Session
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** One scripted step of a fake fetcher: what it does to disk and Room (through [Progress]) and what it answers. */
internal typealias FileStep = suspend (FileCall) -> FetchResult

internal class FileCall(val itemId: String, val part: File, val target: File, val resumeFrom: Long, val validator: String?,
                        val expected: Long?, val storedTotal: Long?, val restarts: Int, val fits: (Long) -> Boolean, val progress: Progress)

/** [OriginalFetch] answering from [steps] in order (then [whole]); each call is recorded. */
internal class FakeFiles : OriginalFetch {
    val calls = mutableListOf<FileCall>()
    val steps = ArrayDeque<FileStep>()

    override suspend fun fetch(route: MediaRoute, itemId: String, part: File, target: File, resumeFrom: Long, validator: String?,
                               expectedBytes: Long?, storedTotal: Long?, restarts: Int, fits: (Long) -> Boolean, progress: Progress): FetchResult {
        val call = FileCall(itemId, part, target, resumeFrom, validator, expectedBytes, storedTotal, restarts, fits, progress)
        calls += call
        return (steps.removeFirstOrNull() ?: whole())(call)
    }

    companion object {
        const val SIZE = 4_000L

        /** The whole file, written and renamed, as the real fetcher ends. */
        fun whole(validator: String = "\"v1\""): FileStep = { c ->
            c.part.parentFile!!.mkdirs()
            c.part.writeBytes(ByteArray(SIZE.toInt()))
            c.progress.saved(SIZE, SIZE, validator, c.restarts)
            c.part.renameTo(c.target)
            FetchResult.Done
        }

        /** [bytes] saved, then [result] (a dropped connection, by default). */
        fun partly(bytes: Long, result: FetchResult = FetchResult.Retry(app.shoalwatch.core.AppError.Kind.NETWORK)): FileStep = { c ->
            c.part.parentFile!!.mkdirs()
            c.part.writeBytes(ByteArray(bytes.toInt()))
            c.progress.saved(bytes, SIZE, "\"v1\"", c.restarts)
            result
        }

        fun answer(result: FetchResult): FileStep = { result }
    }
}

/** [ConversionFetch] over in-memory sessions: [open] hands out a session of [segments] segments; [fetch] answers from [steps]. */
internal class FakeHls(private val clock: TestMonotonic) : ConversionFetch {
    var segments = 5
    val opens = mutableListOf<Resolved>()
    /** The runtime each open was asked to check the playlist against. */
    val runtimes = mutableListOf<Long?>()
    val fetches = mutableListOf<Pair<LiveConversion, Int>>()
    val openSteps = ArrayDeque<FetchResult>()
    /** Each fetch: segments to write (from the start index), then the result. */
    val steps = ArrayDeque<Pair<Int, FetchResult>>()
    /** Called after each segment is written, before its progress is saved. */
    var onSegment: (Int) -> Unit = {}

    override suspend fun open(resolved: Resolved, folder: File, runtimeMs: Long?, space: ConversionSpace): Pair<LiveConversion?, FetchResult> {
        opens += resolved
        runtimes += runtimeMs
        openSteps.removeFirstOrNull()?.let { return null to it }
        File(folder, "hls").apply { deleteRecursively(); mkdirs() }
        val segs = List(segments) { HlsPlaylists.Segment(app.shoalwatch.data.playback.StreamSource("/videos/x/seg$it.ts", emptyList(), false), 2_000) }
        return LiveConversion(resolved.plan.itemId, resolved, segs, clock.ms, space, 1L shl 30, 1_000_000) to FetchResult.Done
    }

    override suspend fun fetch(live: LiveConversion, folder: File, fromSegment: Int, progress: suspend (Int, Int) -> Unit): FetchResult {
        fetches += live to fromSegment
        val (count, result) = steps.removeFirstOrNull() ?: ((live.segments.size - fromSegment) to FetchResult.Done)
        val hls = File(folder, "hls")
        for (n in fromSegment until minOf(fromSegment + count, live.segments.size)) {
            File(hls, "seg-%05d.ts".format(n)).writeBytes(ByteArray(376) { 0x47 })
            onSegment(n)
            progress(n + 1, live.segments.size)
        }
        return result
    }
}

internal class FakeExtras : DownloadExtras {
    val calls = mutableListOf<String>()
    var tracksAudio: List<AudioTrack> = emptyList()
    var artworkFails: Throwable? = null
    override suspend fun tracks(s: Session, key: DownloadKey, folder: File, mediaSourceId: String, audio: List<AudioTrack>, subtitles: List<SubtitleTrack>, quality: DownloadQuality) {
        calls += "tracks"
        tracksAudio = audio
    }
    override suspend fun segments(s: Session, key: DownloadKey) { calls += "segments" }
    override suspend fun artwork(s: Session, itemIds: List<String>) { calls += "artwork"; artworkFails?.let { throw it } }
}

internal class FakeVerifier : PlayableCheck {
    val verdicts = ArrayDeque<Verdict>()
    val verified = mutableListOf<SavedMedia>()
    /** The runtime each check compared the file's duration with. */
    val runtimes = mutableListOf<Long?>()
    var recheckAnswer = true
    override suspend fun verify(media: SavedMedia, expectedBytes: Long?, runtimeMs: Long?, hlsShape: List<Long>?, audioListed: Boolean): Verdict {
        verified += media
        runtimes += runtimeMs
        return verdicts.removeFirstOrNull() ?: Verdict.Good(GOOD)
    }
    override suspend fun recheck(media: SavedMedia, sha256: String) = recheckAnswer

    companion object { val GOOD = "ab".repeat(32) }
}

/** The queue over in-memory Room, with every part faked except the real [PlaybackResolver] (over a fake gateway) and [DownloadPaths]. */
abstract class DownloadQueueTestBase : DownloadTestBase() {
    @get:Rule val tmp = TemporaryFolder()

    internal val files = FakeFiles()
    protected val mono = TestMonotonic()
    internal val hls = FakeHls(mono)
    internal val extras = FakeExtras()
    internal val verifier = FakeVerifier()
    internal val facts = FakeDownloadGateway()
    internal val playback = FakePlaybackGateway()
    internal lateinit var fake: FakeVolumes
    internal lateinit var paths: DownloadPaths
    internal val tokens = mutableSetOf<Session>()
    internal val routes = mutableListOf<String>()
    internal var captions = CaptionDefaults(false, null, 1f)

    @Before fun setUpQueue() {
        fake = FakeVolumes(tmp.root).also { it.free[null] = 1L shl 40 }
        paths = DownloadPaths(fake.volumes)
        tokens += listOf(s1u1, s1u2, s2u1)
    }

    internal fun resolver() = PlaybackResolver(playback, CodecSupport { AndroidDeviceCapabilities.MINIMAL }, { s -> PlaybackFakes.route(s) }, Dispatchers.Unconfined)

    internal fun queue(maxAttempts: Int = 8) = DownloadQueue(
        store, resolver(), { s, id -> routes += id; PlaybackFakes.route(s) }, facts, files, hls, extras, verifier, paths, fake.volumes,
        { s -> s in tokens }, TrackPreferences(db.trackChoiceDao()) { 0L }, { captions }, mono, Dispatchers.Unconfined, maxAttempts,
    )

    internal fun drain(q: DownloadQueue = queue(), stop: () -> Boolean = { false }, statuses: MutableList<QueueStatus>? = null): QueueRun =
        runBlocking { q.drain(stop) { statuses?.add(it) } }

    /**
     * Queues [items] for [s] at [quality] (the server showed them first). A conversion is planned only on a confirmed CanDownload,
     * so the server's facts say true for it, as the queue asks again before it converts (final review N-1).
     */
    internal fun queued(s: Session, vararg items: Item, quality: DownloadQuality = DownloadQuality.ORIGINAL) {
        if (quality != DownloadQuality.ORIGINAL) items.forEach { facts.allow(it.id) }
        seen(s, *items)
        runBlocking { store.add(s, items.map { newDownload(it, quality) }) }
    }

    internal fun download(item: Item, serverId: String = s1u1.serverId): DownloadEntity? = runBlocking { store.get(DownloadKey(serverId, item.id)) }

    /** The original check answers "plays here" this many times. */
    protected fun playable(times: Int = 1) = repeat(times) { playback.answer(fixtureInfo("direct-play.json")) }

    /** A conversion: the track check, then the forced transcode. */
    protected fun convertible() = playback.answer(fixtureInfo("direct-play.json"), fixtureInfo("transcode.json"))

    protected fun folder(item: Item, serverId: String = s1u1.serverId) = paths.itemFolder(DownloadKey(serverId, item.id), StorageKind.INTERNAL, null)!!
}
