package app.shoalwatch.data.sync

import app.shoalwatch.data.library.ItemKind

/** PLAYED and FAVOURITE carry [Change.flag]; POSITION carries [Change.positionMs] (0 = off Continue Watching); PLAY is one finished watch of a saved copy, at [Change.occurredAt]. */
enum class ChangeKind { PLAYED, FAVOURITE, POSITION, PLAY }

/** One change to send. Never printed: it holds an item and a position. */
data class Change(
    val itemId: String,
    val itemKind: ItemKind,
    val kind: ChangeKind,
    val flag: Boolean? = null,
    val positionMs: Long? = null,
    val occurredAt: Long,
    val fromOfflinePlay: Boolean = false,
) {
    init {
        require((kind == ChangeKind.PLAYED || kind == ChangeKind.FAVOURITE) == (flag != null)) { "A flag goes with PLAYED and FAVOURITE only." }
        require((kind == ChangeKind.POSITION) == (positionMs != null && positionMs >= 0)) { "A position goes with POSITION only." }
    }

    override fun toString() = "Change(kind=$kind)"
}

/** What one profile's waiting changes look like to a screen. Printed without [lastSyncedAt] (a date). */
data class SyncStatus(val waiting: Int, val stuck: Int, val needsSignIn: Boolean, val lastSyncedAt: Long?) {
    override fun toString() = "SyncStatus(waiting=$waiting, stuck=$stuck, needsSignIn=$needsSignIn)"

    companion object {
        val NONE = SyncStatus(waiting = 0, stuck = 0, needsSignIn = false, lastSyncedAt = null)
    }
}

enum class DrainResult { EMPTY, WAITING_FOR_CONNECTION, WAITING_FOR_SIGN_IN, BACKING_OFF, STOPPED }

/** The server's copy, validated (Task 4). [playCount] and [lastPlayedAt] null: not sent, or out of range. */
data class ServerUserData(val played: Boolean, val playCount: Int?, val positionMs: Long, val favourite: Boolean, val lastPlayedAt: Long?) {
    override fun toString() = "ServerUserData(<redacted>)"
}

/** The body of POST /UserItems/{id}/UserData: only non-null fields are sent. */
data class UserDataUpdate(val played: Boolean? = null, val playCount: Int? = null, val positionMs: Long? = null, val lastPlayedAt: Long? = null) {
    override fun toString() = "UserDataUpdate(<redacted>)"
}
