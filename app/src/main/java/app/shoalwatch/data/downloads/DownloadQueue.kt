package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.api.MediaRoute
import app.shoalwatch.data.db.downloads.DownloadEntity
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.DownloadStore
import app.shoalwatch.data.downloads.api.DownloadGateway
import app.shoalwatch.data.playback.AudioTrack
import app.shoalwatch.data.playback.CaptionDefaults
import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.PlaybackRequest
import app.shoalwatch.data.playback.PlaybackResolver
import app.shoalwatch.data.playback.TrackChooser
import app.shoalwatch.data.playback.TrackPreferences
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface QueueRun {
    data object Drained : QueueRun
    data class Retry(val kind: AppError.Kind) : QueueRun
    data object Stopped : QueueRun
}

/**
 * Room is the queue; this drains it, one item at a time, writing each step to Room before the next (see "Download queue" in
 * docs/ARCHITECTURE.md). Conversions resume only inside the play session held in [live] (this process, younger than
 * [liveConversionMaxAgeMs]); otherwise the item's conversion starts again from segment 0. Logs fixed words and enum names only.
 */
internal class DownloadQueue(
    private val store: DownloadStore,
    private val resolver: PlaybackResolver,
    private val downloadRoute: suspend (Session, String) -> MediaRoute,
    private val gateway: DownloadGateway,
    private val files: OriginalFetch,
    private val hls: ConversionFetch,
    private val extras: DownloadExtras,
    private val verifier: PlayableCheck,
    private val paths: DownloadPaths,
    private val volumes: Volumes,
    private val hasToken: (Session) -> Boolean,
    private val tracks: TrackPreferences,
    private val captions: () -> CaptionDefaults,
    private val clock: MonotonicClock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxAttempts: Int = 8,
    private val liveConversionMaxAgeMs: Long = 30 * 60_000L,
) : QueueDrain {
    private val drains = Mutex()
    private val starts = Mutex()
    private val live = ConcurrentHashMap<DownloadKey, HeldConversion>()

    /** The download was removed: its remembered conversion goes with it. */
    fun forget(key: DownloadKey) { live.remove(key) }

    /** RUNNING and VERIFYING back to QUEUED, only while no drain runs in this process (at start-up, after process death). */
    suspend fun requeueIfIdle() {
        if (drains.tryLock()) try { store.requeueInterrupted() } finally { drains.unlock() }
    }

    /** Runs [block] while no download can start: Task 12 reads `DownloadStore.sweepInputs()` and sweeps inside it. */
    suspend fun <T> whileNoneStarts(block: suspend () -> T): T = starts.withLock { block() }

    /** Called by the worker. Requeues interrupted rows, then runs QUEUED items in order until none is left or [shouldStop] says so. */
    override suspend fun drain(shouldStop: () -> Boolean, onProgress: suspend (QueueStatus) -> Unit): QueueRun = drains.withLock {
        store.requeueInterrupted()
        var done = 0
        while (true) {
            if (shouldStop()) return@withLock QueueRun.Stopped
            val taken = starts.withLock { store.next()?.let { r -> r to store.claim(r.key) } }
            if (taken == null) {
                onProgress(QueueStatus.Idle)
                return@withLock QueueRun.Drained
            }
            val (row, claimed) = taken
            if (!claimed) continue // paused or removed between the read and the claim
            when (val step = run(QueueJob(row.key, done + 1, done + 1 + store.queuedCount(), onProgress, shouldStop))) {
                QueueStep.Next -> done++
                is QueueStep.Halt -> return@withLock step.run
            }
        }
        @Suppress("UNREACHABLE_CODE") QueueRun.Drained
    }

    /** Called after a playback failure of a saved copy: re-reads it and marks it FAILED(DAMAGED) if the fingerprint differs. */
    suspend fun recheck(key: DownloadKey) {
        val row = store.get(key)?.takeIf { it.state == DownloadState.COMPLETE } ?: return
        val sha = row.sha256 ?: return
        val folder = paths.itemFolder(key, row.storage, row.volumeId) ?: return // the card is out: nothing to say yet
        val media = SavedMedia(key.serverId, key.itemId, folder, QueueSpace.entryOf(paths, row, folder) ?: return, row.container == HLS, row.quality, row.mediaSourceId ?: return)
        if (!verifier.recheck(media, sha)) {
            store.updateIf(key, setOf(DownloadState.COMPLETE)) { it.copy(state = DownloadState.FAILED, failure = DownloadFailure.DAMAGED) }
            AppLog.w(TAG, "download damaged")
        }
    }

    private suspend fun run(j: QueueJob): QueueStep {
        var folder: File? = null
        val step = kotlin.run attempt@{ try {
            val row = current(j.key)
            val s = fetcherOf(j.key) ?: return@attempt failed(j.key, DownloadFailure.SIGN_IN_NEEDED)
            val dir = paths.itemFolder(j.key, row.storage, row.volumeId)?.also { it.mkdirs() } ?: return@attempt failed(j.key, DownloadFailure.STORAGE_MISSING)
            folder = dir
            if (!QueueSpace.fits(volumes, row, QueueSpace.remaining(row))) return@attempt failed(j.key, DownloadFailure.NO_SPACE)
            j.status(null)
            val fetched = if (row.quality == DownloadQuality.ORIGINAL) original(j, s, dir) else conversion(j, s, dir)
            bestEffort { extras.segments(s, j.key) }
            bestEffort { extras.artwork(s, listOfNotNull(row.itemId, row.seasonId, row.seriesId)) }
            verify(j, s, dir, fetched)
        } catch (e: QueueEnds) {
            e.step
        } catch (e: QueueRowGone) {
            live.remove(j.key)
            folder?.let(paths::deleteInside)
            QueueStep.Next
        } catch (e: QueueMoved) {
            QueueStep.Next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Anything unforeseen (a card remounted read-only, say) is one attempt, so the item ends FAILED rather than
            // staying RUNNING at the head of the queue for ever (Tasks 9-10 review I-3). Logged by class only.
            AppLog.w(TAG, "download step failed: ${e.javaClass.simpleName}")
            retry(j.key, (e as? AppError)?.kind ?: AppError.Kind.UNKNOWN)
        } }
        // A removal the step did not see (it ended in a retry or a failure, with no later write to find the row gone), after
        // which a fetcher may have made the folder again (`HlsFetcher.open`'s mkdirs, a part file): no folder is left behind
        // (final review L-5). A new row of the same item cannot be running: this is the one drain.
        folder?.let { dir -> if (store.get(j.key) == null) { live.remove(j.key); paths.deleteInside(dir) } }
        return step
    }

    /** The earliest owner (by when they added it) that can still send. */
    private suspend fun fetcherOf(key: DownloadKey): Session? =
        store.owners(key).map { Session(key.serverId, it) }.firstOrNull(hasToken)

    private suspend fun original(j: QueueJob, s: Session, folder: File): QueueFetched {
        val key = j.key
        val info = server(key) { resolver.checkOriginal(s, key.itemId) }
        val check = server(key) { OriginalChecks.of(info, key.itemId) }
        // The user chose the original: a server change since planning is NOT_PLAYABLE, never a silent conversion.
        val playable = check as? OriginalCheck.Playable ?: throw QueueEnds(failed(key, DownloadFailure.NOT_PLAYABLE))
        val before = current(key)
        playable.sizeBytes?.let { if (!QueueSpace.fits(volumes, before, it - before.doneBytes)) throw QueueEnds(failed(key, DownloadFailure.NO_SPACE)) }
        write(key) { it.copy(mediaSourceId = playable.sourceId, container = playable.container) }
        extras.tracks(s, key, folder, playable.sourceId, playable.audio, playable.subtitles, DownloadQuality.ORIGINAL)
        val part = paths.partFile(folder, playable.container)
        val target = paths.mediaFile(folder, playable.container)
        // A file saved under another container (the server remuxed it since) never stays beside this one (review Minor 9).
        for (ext in DownloadPaths.MEDIA_EXTENSIONS - playable.container) {
            paths.mediaFile(folder, ext).takeIf { it.exists() }?.let(paths::deleteInside)
            paths.partFile(folder, ext).takeIf { it.exists() }?.let(paths::deleteInside)
        }
        val fetched = QueueFetched(target, hls = false, shape = null, audioListed = playable.audio.isNotEmpty(), runtimeMs = playable.runtimeMs)
        // Every byte on the phone comes through ONE sign-in (final review L-7): bytes begun or saved under another owner's
        // (a `.part`, or a `media.<ext>` renamed before the process died) are deleted and fetched again under this one, from
        // zero with no validator of theirs. Only then is this sign-in the fetcher.
        if (!store.isFetcher(key, s.userId)) {
            part.takeIf { it.exists() }?.let(paths::deleteInside)
            target.takeIf { it.exists() }?.let(paths::deleteInside)
            write(key) { it.copy(doneBytes = 0, totalBytes = null, validator = null, restarts = 0) }
            store.markFetcher(key, s.userId)
        }
        // Renamed before the process died under THIS sign-in: only the check is left (Tasks 11-12 review Minor 2).
        if (target.exists() && !part.exists()) return fetched
        var route = server(key) { downloadRoute(s, key.itemId) }
        var rerouted = false
        while (true) {
            val row = current(key)
            // The one space rule, for a size only the response states (final review M-2).
            val fits = { bytes: Long -> QueueSpace.fits(volumes, row, bytes) }
            val result = files.fetch(route, key.itemId, part, target, row.doneBytes, row.validator, playable.sizeBytes, row.totalBytes, row.restarts, fits, fileProgress(j, row.doneBytes))
            when (result) {
                FetchResult.Done -> {
                    store.confirm(key, s.userId) // /Download served it to this sign-in: the server's own permission check passed
                    return fetched
                }
                is FetchResult.Failed -> {
                    // Refused for this owner: another owner may still fetch it under their own permission (review I-2 a).
                    if (result.failure == DownloadFailure.NOT_ALLOWED && store.dropRefusedOwner(key, s.userId)) {
                        store.updateIf(key, ACTIVE) { it.copy(state = DownloadState.QUEUED) }
                        throw QueueEnds(QueueStep.Next)
                    }
                    throw QueueEnds(failed(key, result.failure))
                }
                FetchResult.Unauthorised -> throw QueueEnds(unauthorised(key, s))
                FetchResult.RestartConversion -> throw QueueEnds(retry(key, AppError.Kind.SERVER_ERROR))
                is FetchResult.Retry -> {
                    if (result.kind != AppError.Kind.OFFLINE || rerouted) throw QueueEnds(retry(key, result.kind))
                    rerouted = true // a stale route: one new route, then the same file again
                    route = server(key) { downloadRoute(s, key.itemId) }
                }
            }
        }
    }

    /** Saves all four values; a cut to zero after bytes were held (a 200 to a resume) is an attempt. */
    private fun fileProgress(j: QueueJob, resumedFrom: Long): Progress {
        var last = resumedFrom
        return Progress { done, total, validator, restarts ->
            val restarted = done == 0L && last > 0 && total != null
            last = done
            var attempts = 0
            write(j.key) { r -> r.copy(doneBytes = done, totalBytes = total, validator = validator, restarts = restarts, attempts = (if (restarted) r.attempts + 1 else r.attempts).also { attempts = it }) }
            if (attempts >= maxAttempts) throw QueueEnds(failed(j.key, DownloadFailure.SERVER_ERROR))
            j.status(total?.takeIf { it > 0 }?.let { done.toFloat() / it })
            if (j.stop()) throw QueueEnds(stopped(j.key))
        }
    }

    private suspend fun conversion(j: QueueJob, s: Session, folder: File): QueueFetched {
        val key = j.key
        var rerouted = false
        while (true) {
            val row = current(key)
            val held = live[key]?.takeIf { it.isFor(row, s) && clock.nowMs() - it.conv.startedAtMs < liveConversionMaxAgeMs }
            var conv = held?.conv
            var from = row.segmentsDone
            var audioListed = true
            if (conv == null) {
                live.remove(key)
                val opened = open(key, s, folder)
                conv = opened.first
                audioListed = opened.second
                from = 0
            }
            when (val result = hls.fetch(conv, folder, from, segmentProgress(j))) {
                FetchResult.Done -> {
                    live.remove(key)
                    return QueueFetched(
                        paths.localPlaylist(paths.hlsFolder(folder)), hls = true, shape = conv.segments.map { it.durationMs }, audioListed = audioListed,
                        runtimeMs = sourceRuntime(conv.resolved),
                    )
                }
                is FetchResult.Failed -> { live.remove(key); throw QueueEnds(failed(key, result.failure)) }
                FetchResult.Unauthorised -> { live.remove(key); throw QueueEnds(unauthorised(key, s)) }
                FetchResult.RestartConversion -> { live.remove(key); restartConversion(key) }
                is FetchResult.Retry -> {
                    // The live conversion stays in memory, so a network blip resumes the same session.
                    if (result.kind != AppError.Kind.OFFLINE || rerouted) throw QueueEnds(retry(key, result.kind))
                    rerouted = true
                    val again = server(key) { resolver.reroute(conv.resolved) }
                    live[key] = HeldConversion(LiveConversion(conv.itemId, again, conv.segments, conv.startedAtMs, conv.space, conv.budgetBytes, conv.bytesPerSecond), row.requestedAt, row.quality, s)
                }
            }
        }
    }

    /**
     * A fresh play session: the item's tracks (a PlaybackInfo that sets up no conversion) give the remembered audio language's
     * track; then a forced transcode at the row's quality, which must be HLS. Returns it, kept in [live], and whether it has audio.
     */
    private suspend fun open(key: DownloadKey, s: Session, folder: File): Pair<LiveConversion, Boolean> {
        val row = current(key)
        val info = server(key) { resolver.checkOriginal(s, key.itemId) }
        val source = info.sources.firstOrNull { JellyfinId.parseOrNull(it.id)?.value == key.itemId } ?: info.sources.firstOrNull()
        val remembered = tracks.get(s, row.seriesId)
        val audio = source?.let { TrackChooser.choose(it.audio, it.subtitles, remembered, captions(), it.defaultAudioIndex, null).audioIndex } ?: row.audioIndex
        val quality = checkNotNull(row.quality.quality) { "Not a conversion." }
        val resolved = server(key) { resolver.resolve(s, PlaybackRequest(key.itemId, quality, audioIndex = audio, forceTranscode = true)) }
        val plan = resolved.plan
        if (plan.method != PlayMethod.TRANSCODE || !plan.source.hls) throw QueueEnds(failed(key, DownloadFailure.SERVER_ERROR))
        val own: List<AudioTrack> = plan.audioTracks.filter { it.index == (plan.audioIndex ?: audio) }.ifEmpty { plan.audioTracks.take(1) }
        extras.tracks(s, key, folder, plan.mediaSourceId, own, plan.subtitleTracks, row.quality)
        // The playlist's length is checked against the converted source's own runtime, else the item's (W-1); the size
        // estimate stays the item's.
        val (opened, result) = hls.open(resolved, folder, sourceRuntime(resolved) ?: row.runtimeMs, ConversionSpace(row.storage, row.volumeId, QueueSpace.estimate(row)))
        if (opened == null) {
            when (result) {
                is FetchResult.Failed -> throw QueueEnds(failed(key, result.failure))
                FetchResult.Unauthorised -> throw QueueEnds(unauthorised(key, s))
                is FetchResult.Retry -> throw QueueEnds(retry(key, result.kind))
                else -> { restartConversion(key); return open(key, s, folder) }
            }
        }
        write(key) {
            it.copy(segmentsDone = 0, segmentsTotal = opened.segments.size, mediaSourceId = plan.mediaSourceId, container = HLS,
                doneBytes = 0, totalBytes = null, validator = null, audioIndex = own.firstOrNull()?.index ?: it.audioIndex)
        }
        // A fresh session (its old segments deleted by `open`) under this sign-in, resumed only by it (`HeldConversion.isFor`).
        store.markFetcher(key, s.userId)
        live[key] = HeldConversion(opened, row.requestedAt, row.quality, s)
        return opened to own.isNotEmpty()
    }

    /** The session is gone: from segment 0 with a new one, counted as an attempt. */
    private suspend fun restartConversion(key: DownloadKey) {
        var attempts = 0
        write(key) { it.copy(segmentsDone = 0, attempts = (it.attempts + 1).also { a -> attempts = a }) }
        if (attempts >= maxAttempts) throw QueueEnds(failed(key, DownloadFailure.SERVER_ERROR))
    }

    private fun segmentProgress(j: QueueJob): suspend (Int, Int) -> Unit = { done, total ->
        write(j.key) { it.copy(segmentsDone = done, segmentsTotal = total) }
        j.status(if (total > 0) done.toFloat() / total else null)
        if (j.stop()) throw QueueEnds(stopped(j.key))
    }

    /** VERIFYING, then COMPLETE; a first bad check deletes the media (the extras stay) and queues it from 0; a second is CHECK_FAILED with nothing left. */
    private suspend fun verify(j: QueueJob, s: Session, folder: File, fetched: QueueFetched): QueueStep {
        write(j.key) { it.copy(state = DownloadState.VERIFYING) }
        val row = current(j.key)
        val media = SavedMedia(j.key.serverId, j.key.itemId, folder, fetched.entry, fetched.hls, row.quality, row.mediaSourceId.orEmpty())
        val runtime = fetched.runtimeMs ?: row.runtimeMs // the source downloaded, not the item's headline runtime (W-1)
        val verdict = if (!QueueSpace.names(paths, row, folder, fetched.entry)) Verdict.Bad(RECORD) else verifier.verify(media, if (fetched.hls) null else row.totalBytes, runtime, fetched.shape, fetched.audioListed)
        val at = store.stamp()
        when (verdict) {
            is Verdict.Good -> {
                // Nobody keeps a copy fetched under another profile's permission (review I-2 a).
                store.dropUnconfirmedOwners(j.key, s.userId)
                write(j.key) { it.copy(state = DownloadState.COMPLETE, failure = null, sha256 = verdict.sha256, completedAt = at, verifiedAt = at, attempts = 0) }
            }
            is Verdict.Bad -> {
                AppLog.w(TAG, "download check failed: ${verdict.reason}")
                if (row.verifiedAt != null) {
                    paths.deleteInside(folder)
                    return failed(j.key, DownloadFailure.CHECK_FAILED)
                }
                if (fetched.hls) paths.deleteInside(paths.hlsFolder(folder)) else {
                    paths.deleteInside(fetched.entry)
                    paths.partFile(folder, row.container ?: return failed(j.key, DownloadFailure.CHECK_FAILED)).let(paths::deleteInside)
                }
                write(j.key) {
                    it.copy(state = DownloadState.QUEUED, doneBytes = 0, totalBytes = null, validator = null, restarts = 0, segmentsDone = 0, verifiedAt = at)
                }
            }
        }
        return QueueStep.Next
    }

    /** Task 1's rule: ONE ServerApi call tells a dead token (it signs the account out) from a server refusing it for media. */
    private suspend fun unauthorised(key: DownloadKey, s: Session): QueueStep = try {
        gateway.facts(s, listOf(key.itemId))
        failed(key, DownloadFailure.SERVER_AUTH_REFUSED)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        when (val kind = AppError.from(e).kind) {
            AppError.Kind.UNAUTHORISED, AppError.Kind.NOT_SIGNED_IN -> failed(key, DownloadFailure.SIGN_IN_NEEDED)
            else -> retry(key, kind)
        }
    }

    /** A server call: its AppError becomes this item's failure, or a retry. */
    private suspend fun <T> server(key: DownloadKey, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: QueueEnds) {
        throw e
    } catch (e: Exception) {
        throw QueueEnds(
            when (val kind = AppError.from(e).kind) {
                AppError.Kind.UNAUTHORISED, AppError.Kind.NOT_SIGNED_IN -> failed(key, DownloadFailure.SIGN_IN_NEEDED)
                AppError.Kind.FORBIDDEN, AppError.Kind.PLAYBACK_NOT_ALLOWED -> failed(key, DownloadFailure.NOT_ALLOWED)
                AppError.Kind.SERVER_NOT_FOUND -> failed(key, DownloadFailure.GONE_FROM_SERVER)
                AppError.Kind.NO_COMPATIBLE_STREAM -> failed(key, DownloadFailure.NOT_PLAYABLE)
                else -> retry(key, kind)
            },
        )
    }

    private suspend fun failed(key: DownloadKey, given: DownloadFailure): QueueStep {
        live.remove(key)
        val failure = if (given == DownloadFailure.NO_SPACE && cardGone(key)) DownloadFailure.STORAGE_MISSING else given
        if (store.updateIf(key, ACTIVE) { it.copy(state = DownloadState.FAILED, failure = failure) } == DownloadStore.Written.DONE) {
            AppLog.w(TAG, "download failed: ${failure.name}")
        }
        return QueueStep.Next
    }

    /** One more attempt used: back in the queue and this drain stops (the worker backs off), or SERVER_ERROR at [maxAttempts]. */
    private suspend fun retry(key: DownloadKey, kind: AppError.Kind): QueueStep {
        if (cardGone(key)) return failed(key, DownloadFailure.STORAGE_MISSING) // a pulled card is not a network fault (review Minor 5)
        var attempts = 0
        if (store.updateIf(key, ACTIVE) { it.copy(attempts = (it.attempts + 1).also { a -> attempts = a }) } != DownloadStore.Written.DONE) return QueueStep.Next
        if (attempts >= maxAttempts) return failed(key, DownloadFailure.SERVER_ERROR)
        store.updateIf(key, ACTIVE) { it.copy(state = DownloadState.QUEUED) }
        return QueueStep.Halt(QueueRun.Retry(kind))
    }

    private suspend fun stopped(key: DownloadKey): QueueStep {
        store.updateIf(key, ACTIVE) { it.copy(state = DownloadState.QUEUED) }
        return QueueStep.Halt(QueueRun.Stopped)
    }

    /** The row's card is no longer mounted (a pulled card reads as no space or an I/O error). */
    private suspend fun cardGone(key: DownloadKey): Boolean =
        store.get(key)?.let { it.storage == StorageKind.REMOVABLE && paths.itemFolder(key, it.storage, it.volumeId) == null } == true

    private suspend fun current(key: DownloadKey): DownloadEntity = store.get(key) ?: throw QueueRowGone()

    /** Writes only while the item is still this queue's (RUNNING or VERIFYING); a pause stops it, a removal cleans it up. */
    private suspend fun write(key: DownloadKey, change: (DownloadEntity) -> DownloadEntity) {
        when (store.updateIf(key, ACTIVE, change)) {
            DownloadStore.Written.DONE -> Unit
            DownloadStore.Written.MOVED -> throw QueueMoved()
            DownloadStore.Written.GONE -> throw QueueRowGone()
        }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { AppLog.w(TAG, "download extras skipped: ${e.javaClass.simpleName}") }
    }

    private val DownloadEntity.key get() = DownloadKey(serverId, itemId)

    /** The converted source's own runtime (bounded by the mapper), when positive. */
    private fun sourceRuntime(resolved: app.shoalwatch.data.playback.Resolved): Long? = resolved.plan.runtimeMs?.takeIf { it > 0 }

    private companion object {
        const val TAG = "DownloadQueue"
        const val RECORD = "record"
        val ACTIVE = setOf(DownloadState.RUNNING, DownloadState.VERIFYING)
        const val HLS = QueueSpace.HLS
    }
}
