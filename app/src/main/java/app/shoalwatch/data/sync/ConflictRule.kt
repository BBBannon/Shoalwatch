package app.shoalwatch.data.sync

import app.shoalwatch.data.db.sync.OutboxEntity
import app.shoalwatch.data.library.ItemKind

/** What the sender does with one outbox row, given the server's copy. */
internal sealed interface Step {
    /** The server already has it: confirm without sending. */
    data object AlreadyThere : Step

    /** The server holds a newer change: drop this one; the server's copy is shown. */
    data object ServerIsNewer : Step

    data class Update(val body: UserDataUpdate) : Step
    data object MarkPlayed : Step
    data object MarkUnplayed : Step
    data class Favourite(val on: Boolean) : Step
}

/**
 * Pure: what to send for a row, checked against the server's copy (see "Sync outbox" in docs/ARCHITECTURE.md). Every value sent is
 * absolute, so a retry can never count a play twice: a PLAY's count is worked out once, stored before the request leaves, and resent
 * unchanged; no request carries `datePlayed`. "Newer on the server" leans to the server inside [MARGIN_MS], because the phone's and
 * the server's clocks cannot be compared exactly and the server's copy is the one that cannot lose "watched".
 */
internal object ConflictRule {
    const val MARGIN_MS = 120_000L

    /** [server] null: unknown (no user-data route). Needs no server copy for FAVOURITE or a container's PLAYED. */
    fun decide(row: OutboxEntity, server: ServerUserData?): Step {
        val newer = server?.lastPlayedAt?.let { it >= row.occurredAt - MARGIN_MS } == true
        return when (row.kind) {
            ChangeKind.FAVOURITE -> Step.Favourite(row.flag == true)
            ChangeKind.PLAYED -> when {
                row.flag != true -> when {
                    server != null && !server.played -> Step.AlreadyThere
                    server != null && newer -> Step.ServerIsNewer // watched is never undone by a sync
                    else -> Step.MarkUnplayed
                }
                !row.itemKind.isPlayable() -> Step.MarkPlayed
                server?.played == true -> Step.AlreadyThere
                else -> Step.Update(UserDataUpdate(played = true, playCount = server?.let { maxOf(it.playCount ?: 0, 1) }, positionMs = 0))
            }
            ChangeKind.POSITION -> {
                val p = row.positionMs ?: 0
                if (newer) Step.ServerIsNewer else Step.Update(UserDataUpdate(positionMs = p, lastPlayedAt = row.occurredAt.takeIf { p > 0 }))
            }
            ChangeKind.PLAY -> {
                val sent = row.sentPlayCount
                val serverCount = server?.playCount
                if (row.sentOnce && sent != null && serverCount != null && serverCount >= sent) return Step.AlreadyThere
                Step.Update(
                    UserDataUpdate(
                        played = true,
                        playCount = sent ?: serverCount?.plus(1),
                        positionMs = 0L.takeIf { !newer },
                        lastPlayedAt = row.occurredAt.takeIf { !newer },
                    ),
                )
            }
        }
    }

    fun needsServerCopy(row: OutboxEntity): Boolean = when (row.kind) {
        ChangeKind.FAVOURITE -> false
        ChangeKind.PLAYED -> row.flag != true || row.itemKind.isPlayable()
        ChangeKind.POSITION, ChangeKind.PLAY -> true
    }

    /** Films and episodes: the server's own "played" is theirs alone, so an absolute update is safe. */
    private fun ItemKind.isPlayable() = this == ItemKind.MOVIE || this == ItemKind.EPISODE
}
