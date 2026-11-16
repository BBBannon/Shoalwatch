package app.shoalwatch.data.downloads

import app.shoalwatch.data.servers.Session
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
}
