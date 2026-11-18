package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.downloads.HousekeepingDao
import app.shoalwatch.data.db.downloads.ReleaseStore
import app.shoalwatch.data.db.downloads.RemoveOutcome
import app.shoalwatch.data.db.downloads.VolumeBytesRow
import app.shoalwatch.data.db.library.toModel
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.security.PinCheck
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import app.shoalwatch.security.HouseholdGuard
import app.shoalwatch.security.HouseholdLockedException
import kotlinx.coroutines.launch

/**
 * Implements [Housekeeping]. Watched marks go through [playstate] (each profile's own outbox, sent with its own token); the rule
 * is [ReleaseStore]'s; the files go through [files] once the player lets go. Actions run in [scope], the app's, so a press during
 * the credits finishes even if the player closes. [hasToken]: the profile is signed in. [onWatched]: auto-download next, after the
 * release; [beforeRelease]: its request is written first, while the anchor's download (and so its quality) can still be read.
 */
class HousekeepingRepository internal constructor(
    private val releases: ReleaseStore,
    private val dao: HousekeepingDao,
    private val playstate: Playstate,
    private val files: ReleasedFiles,
    private val onWatched: (Session, String) -> Unit,
    private val hasToken: (Session) -> Boolean,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val beforeRelease: suspend (Session, String) -> Unit = { _, _ -> },
    /** Storage: the mounted volumes, and `Downloads.remove` (Milestone 4's own-download removal, files with the last owner). */
    private val volumes: Volumes,
    private val remove: suspend (Session, String) -> RemoveOutcome,
    /** The press while the player has the copy open (it remembers it for the stop) and the local-only mark (review I1). */
    private val inPlayer: (Session, String) -> Boolean = { _, _ -> false },
    private val markHere: suspend (Session, String) -> Unit = { _, _ -> },
    /** The household check: "Delete shared downloads" is a household setting (Milestone 7). */
    private val household: HouseholdGuard? = null,
    /** Profile protection for "Watched together" (security review M8 F12); null: no profile is protected (tests). */
    private val sharerLocks: SharerLocks? = null,
) : Housekeeping {
    private val proofs = TogetherProofs()

    override fun profileSettings(s: Session): Flow<ProfileSettings> = guarded(ProfileSettings()) {
        dao.observeProfile(s.serverId, s.userId).map { row -> row?.let { ProfileSettings(it.afterFinish, it.autoNextCount) } ?: ProfileSettings() }
    }

    override suspend fun setAfterFinish(s: Session, value: AfterFinish) = action {
        dao.insertProfileIfMissing(s.serverId, s.userId)
        dao.updateAfterFinish(s.serverId, s.userId, value)
        Unit
    }

    override suspend fun setAutoNext(s: Session, count: Int) {
        require(count in AUTO_NEXT) { "Not an auto-download count." }
        action {
            dao.insertProfileIfMissing(s.serverId, s.userId)
            dao.updateAutoNext(s.serverId, s.userId, count)
            Unit
        }
    }

    override fun sharedDelete(serverId: String): Flow<SharedDelete> {
        if (!isId(serverId)) return flowOf(SharedDelete.ANYONE)
        return guarded(SharedDelete.ANYONE) { dao.observeDeleteShared(serverId).map { it ?: SharedDelete.ANYONE } }
    }

    /** @throws HouseholdLockedException (outside [action], so it reaches the screen as itself); nothing is changed. */
    override suspend fun setSharedDelete(s: Session, value: SharedDelete) {
        household?.require()
        action {
            dao.insertServerSettingsIfMissing(s.serverId)
            dao.updateDeleteShared(s.serverId, value)
        }
    }

    override suspend fun protectedSharers(s: Session, itemId: String): List<AccountSummary> = action {
        val locks = sharerLocks ?: return@action emptyList()
        releases.sharers(s, itemId, signedIn(s)).map { Session(s.serverId, it) }.filter { lockedOrUnknown(locks, it) }
            .map { AccountSummary(it.serverId, it.userId, locks.name(it).orEmpty()) }
    }

    /** Only a protected sharer's PIN is checked; a right one is a proof for this one press ([TogetherProofs]). */
    override suspend fun proveSharer(s: Session, sharerUserId: String, itemId: String, pin: CharArray): PinCheck = try {
        val sharer = Session(s.serverId, sharerUserId)
        val locks = sharerLocks
        if (locks == null || !lockedOrUnknown(locks, sharer)) {
            PinCheck.NotProtected
        } else {
            locks.check(sharer, pin).also { if (it == PinCheck.Correct) proofs.record(s, sharer, itemId) }
        }
    } finally {
        pin.fill('\u0000')
    }

    /** A protection that cannot be read counts as protected: that profile is left out, never marked unasked. */
    private suspend fun lockedOrUnknown(locks: SharerLocks, s: Session): Boolean = try {
        locks.isProtected(s)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w(TAG, "a sharer's protection could not be read", e)
        true
    }

    override suspend fun releaseChoice(s: Session, itemId: String): ReleaseChoice = action { releases.choice(s, itemId, signedIn(s)) }

    override suspend fun release(s: Session, itemId: String, together: Boolean): ReleaseOutcome = releaseWith(s, itemId, together).outcome

    override suspend fun releaseTogether(s: Session, itemId: String): TogetherRelease = releaseWith(s, itemId, together = true)

    override suspend fun dropProofs(s: Session, itemId: String) = proofs.drop(s, itemId)

    /** Every press ends its proofs, used or not (review M-2): a later press can never reuse them. */
    private suspend fun releaseWith(s: Session, itemId: String, together: Boolean): TogetherRelease = try {
        releaseOnce(s, itemId, together)
    } finally {
        proofs.drop(s, itemId)
    }

    private suspend fun releaseOnce(s: Session, itemId: String, together: Boolean): TogetherRelease = action {
        var leftOut = false
        // Nothing changes, not even watched, for a download this profile may not see (the same answer as none at all).
        if (!releases.choice(s, itemId, signedIn(s)).canRelease) return@action TogetherRelease(ReleaseOutcome.NOT_YOURS, leftOut = false)
        // In the player, the stop's PLAY carries this watch to the server; a PLAYED tick as well would count it twice (review I1).
        val here = inPlayer(s, itemId)
        if (here) markHere(s, itemId) else playstate.setPlayed(s, itemId, true)
        if (together) {
            for (other in releases.sharers(s, itemId, signedIn(s))) {
                val sharer = Session(s.serverId, other)
                // A protected profile's account is written only with its own PIN for this press (security review M8 F12).
                val locks = sharerLocks
                if (locks != null && lockedOrUnknown(locks, sharer) && !proofs.consume(s, sharer, itemId)) {
                    leftOut = true
                    continue
                }
                try {
                    playstate.setPlayed(sharer, itemId, true) // that profile's OWN outbox, sent as itself
                } catch (e: AppError) {
                    // Skipped, never half-done (Tasks 11-12 review M3): SERVER_NOT_FOUND means it is no longer shown to that profile;
                    // any other failure is logged by kind and the release goes on (EVERYONE then keeps it if that profile has not watched).
                    if (e.kind != AppError.Kind.SERVER_NOT_FOUND) AppLog.w(TAG, "a profile watching together was not marked: ${e.kind}")
                }
            }
        }
        remember(s, itemId)
        val outcome = apply(releases.release(s, itemId, watchedBy = setOf(s.userId))) // the rule is told directly that [s] watched
        if (!here) onWatched(s, itemId) // in the player, the stop's [onLocalFinished] runs it, once (Tasks 13-14 review M2)
        TogetherRelease(outcome, leftOut)
    }

    override fun storage(s: Session): Flow<StorageView> = guarded(EMPTY_STORAGE) {
        combine(
            dao.observeSeriesUse(s.serverId, s.userId), dao.observeFilmUse(s.serverId, s.userId), dao.observeServerBytes(s.serverId),
            dao.observeVolumeBytes(), dao.observeAutoNextBlocked(s.serverId, s.userId),
        ) { seriesRows, filmRows, serverBytes, volumeRows, blocked ->
            val series = seriesRows.map {
                SeriesUse(ItemWithUserData(it.item.toModel(), it.userData?.toModel()), it.episodes, it.watched, it.bytes, it.deletableBytes, it.ownedEpisodes)
            }.sortedByDescending { it.bytes }
            val films = filmRows.map {
                FilmUse(ItemWithUserData(it.item.toModel(), it.userData?.toModel()), it.bytes, it.userData?.played == true, it.ownedByMe)
            }.sortedByDescending { it.bytes }
            // Everything else on this server is one number: no title, no count (SAFEGUARDING).
            val mine = series.sumOf { it.bytes } + films.sumOf { it.bytes }
            StorageView(volumeUse(volumeRows), series, films, (serverBytes - mine).coerceAtLeast(0), blocked)
        }
    }

    /** Each mounted volume with what downloads use on it; then a card that is out but still holds downloads. */
    private fun volumeUse(rows: List<VolumeBytesRow>): List<VolumeUse> {
        val choices = try { volumes.choices() } catch (e: Exception) { emptyList() }
        fun used(kind: StorageKind, id: String?) = rows.filter { it.storage == kind && (kind == StorageKind.INTERNAL || it.volumeId == id) }.sumOf { it.bytes }
        val here = choices.map { VolumeUse(it.kind, it.volumeId, it.freeBytes, used(it.kind, it.volumeId), it.available) }
        val mounted = choices.filter { it.kind == StorageKind.REMOVABLE }.mapNotNull { it.volumeId }.toSet()
        val out = rows.filter { it.storage == StorageKind.REMOVABLE && it.volumeId !in mounted && it.bytes > 0 }
            .groupBy { it.volumeId }.map { (id, r) -> VolumeUse(StorageKind.REMOVABLE, id, 0, r.sumOf { it.bytes }, available = false) }
        return here + out
    }

    override suspend fun watchedSummary(s: Session): WatchedSummary = action {
        val previews = dao.watchedPlayable(s.serverId, s.userId).map { releases.preview(s, it) }
        val gone = previews.filter { it.outcome == ReleaseStore.Outcome.DELETED }
        WatchedSummary(
            count = gone.size + previews.count { it.outcome == ReleaseStore.Outcome.KEPT_FOR_OTHERS },
            bytes = gone.sumOf { it.bytes }, sharedForEveryone = gone.count { it.sharedForEveryone },
        )
    }

    override suspend fun deleteWatched(s: Session): Int = action {
        var released = 0
        for (id in dao.watchedPlayable(s.serverId, s.userId)) {
            val outcome = apply(releases.release(s, id)) // each one re-checks that [s] may see it
            if (outcome == ReleaseOutcome.DELETED || outcome == ReleaseOutcome.KEPT_FOR_OTHERS) released++
        }
        released
    }

    override suspend fun deleteSeries(s: Session, seriesId: String): Int {
        if (!isId(seriesId)) return 0
        return action { dao.ownSeriesDownloads(s.serverId, s.userId, seriesId).count { remove(s, it) is RemoveOutcome.Deleted } }
    }

    /** `LocalPlaybackReports`' onFinished (the stop already marked it watched): ALWAYS releases, with no second mark; then auto-download next. */
    internal fun onLocalFinished(s: Session, itemId: String) {
        scope.launch(io) {
            try {
                remember(s, itemId)
                if (dao.profile(s.serverId, s.userId)?.afterFinish == AfterFinish.ALWAYS) apply(releases.release(s, itemId, watchedBy = setOf(s.userId)))
                onWatched(s, itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "finished download not tidied", e)
            }
        }
    }

    /** Auto-download next's request; its failure never stops a release. */
    private suspend fun remember(s: Session, itemId: String) {
        try {
            beforeRelease(s, itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "next episodes not remembered", e)
        }
    }

    private fun apply(result: ReleaseStore.Result): ReleaseOutcome = when (result) {
        is ReleaseStore.Result.Deleted -> { files.delete(result.key, result.storage, result.volumeId); ReleaseOutcome.DELETED }
        ReleaseStore.Result.KeptForOthers -> ReleaseOutcome.KEPT_FOR_OTHERS
        ReleaseStore.Result.KeptUntilEveryoneWatched -> ReleaseOutcome.KEPT_UNTIL_EVERYONE_WATCHED
        ReleaseStore.Result.NotYours -> ReleaseOutcome.NOT_YOURS
    }

    private fun signedIn(s: Session): (String) -> Boolean = { userId -> hasToken(Session(s.serverId, userId)) }

    private fun <T> guarded(empty: T, flow: () -> Flow<T>): Flow<T> = try {
        flow().distinctUntilChanged().catch { e -> if (e is CancellationException) throw e; AppLog.w(TAG, "housekeeping read failed", e); emit(empty) }.flowOn(io)
    } catch (e: Exception) {
        flowOf(empty)
    }

    /** In the app scope on [io]; anything that is not an AppError is UNKNOWN. */
    private suspend fun <T> action(block: suspend () -> T): T = scope.async(io) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError) {
            throw e
        } catch (e: Exception) {
            throw AppError.of(AppError.Kind.UNKNOWN, e)
        }
    }.await()

    private fun isId(id: String) = JellyfinId.parseOrNull(id)?.value == id

    private companion object {
        const val TAG = "Housekeeping"
        val AUTO_NEXT = setOf(0, 1, 2, 3, 5)
        val EMPTY_STORAGE = StorageView(emptyList(), emptyList(), emptyList(), 0, false)
    }
}
