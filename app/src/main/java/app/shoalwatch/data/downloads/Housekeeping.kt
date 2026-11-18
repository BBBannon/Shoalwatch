package app.shoalwatch.data.downloads

import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.PinCheck
import kotlinx.coroutines.flow.Flow

/**
 * What screens use for watch-and-delete and the per-profile download settings (see "Watch-and-delete" in docs/ARCHITECTURE.md).
 * Every call is for one Session and acts only as it. Flows never throw: a failure or a malformed id is the default value.
 * Actions throw `AppError` (or IllegalArgumentException for a value out of range) and finish even if the screen goes.
 */
interface Housekeeping {
    fun profileSettings(s: Session): Flow<ProfileSettings>
    suspend fun setAfterFinish(s: Session, value: AfterFinish)

    /** 0, 1, 2, 3 or 5; anything else is IllegalArgumentException. */
    suspend fun setAutoNext(s: Session, count: Int)

    /** "Delete shared downloads" for the server (ANYONE until set). */
    fun sharedDelete(serverId: String): Flow<SharedDelete>

    /** Any signed-in profile of that server may change it, as sharing itself. */
    suspend fun setSharedDelete(s: Session, value: SharedDelete)

    /** What the prompt may offer [s] for the item; a download [s] may not see answers as no download at all. */
    suspend fun releaseChoice(s: Session, itemId: String): ReleaseChoice

    /**
     * Marks it watched for [s] (and, with [together], for every other profile counted in [ReleaseChoice]), then applies the rule.
     * A download [s] may not see is NOT_YOURS with nothing changed, not even its watched state.
     */
    suspend fun release(s: Session, itemId: String, together: Boolean): ReleaseOutcome

    /**
     * "Watched together" for [s]: as `release(s, itemId, together = true)`, and [TogetherRelease.leftOut] says whether a protected
     * sharer was left out (no proof of its PIN for this press, or its protection could not be read).
     */
    suspend fun releaseTogether(s: Session, itemId: String): TogetherRelease = TogetherRelease(release(s, itemId, together = true), leftOut = false)

    /** A new "Watched together" press (or one abandoned) of [itemId] by [s]: every PIN proof made for an earlier one goes. */
    suspend fun dropProofs(s: Session, itemId: String) {}

    /**
     * The protected profiles "Watched together" of [itemId] would mark for [s] (security review M8 F12), with their names for the
     * PIN step. Each is marked only after [proveSharer] with its own PIN; without one it is left out.
     */
    suspend fun protectedSharers(s: Session, itemId: String): List<AccountSummary> = emptyList()

    /** Checks [sharerUserId]'s own PIN (zeroes [pin]); Correct lets [s]'s next "Watched together" of [itemId] mark that profile, once, within a minute. */
    suspend fun proveSharer(s: Session, sharerUserId: String, itemId: String, pin: CharArray): PinCheck {
        pin.fill('\u0000')
        return PinCheck.NotProtected
    }

    /** What uses space, as [s] may see it: its series and films by size, the volumes, and everyone else's downloads as one number. */
    fun storage(s: Session): Flow<StorageView>

    /** What [deleteWatched] would do now: the same rule, nothing changed. */
    suspend fun watchedSummary(s: Session): WatchedSummary

    /** The watch-and-delete rule for every download [s] may see and has watched; how many left its downloads ([WatchedSummary.count]). */
    suspend fun deleteWatched(s: Session): Int

    /** Removes [s]'s OWN downloads of the series (the files go with the last owner); how many of them were deleted from the phone. */
    suspend fun deleteSeries(s: Session, seriesId: String): Int
}
