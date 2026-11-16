package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.downloads.HousekeepingDao
import app.shoalwatch.data.db.downloads.ReleaseStore
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
) : Housekeeping {
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

    override suspend fun setSharedDelete(s: Session, value: SharedDelete) = action {
        dao.insertServerSettingsIfMissing(s.serverId)
        dao.updateDeleteShared(s.serverId, value)
        Unit
    }

    override suspend fun releaseChoice(s: Session, itemId: String): ReleaseChoice = action { releases.choice(s, itemId, signedIn(s)) }

    override suspend fun release(s: Session, itemId: String, together: Boolean): ReleaseOutcome = action {
        // Nothing changes, not even watched, for a download this profile may not see (the same answer as none at all).
        if (!releases.choice(s, itemId, signedIn(s)).canRelease) return@action ReleaseOutcome.NOT_YOURS
        playstate.setPlayed(s, itemId, true)
        if (together) {
            for (other in releases.sharers(s, itemId, signedIn(s))) {
                try {
                    playstate.setPlayed(Session(s.serverId, other), itemId, true) // that profile's OWN outbox, sent as itself
                } catch (e: AppError) {
                    if (e.kind != AppError.Kind.SERVER_NOT_FOUND) throw e // the item is no longer shown to it: skipped
                }
            }
        }
        remember(s, itemId)
        val outcome = apply(releases.release(s, itemId))
        onWatched(s, itemId)
        outcome
    }

    /** `LocalPlaybackReports`' onFinished (the stop already marked it watched): ALWAYS releases, with no second mark; then auto-download next. */
    internal fun onLocalFinished(s: Session, itemId: String) {
        scope.launch(io) {
            try {
                remember(s, itemId)
                if (dao.profile(s.serverId, s.userId)?.afterFinish == AfterFinish.ALWAYS) apply(releases.release(s, itemId))
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
    }
}
