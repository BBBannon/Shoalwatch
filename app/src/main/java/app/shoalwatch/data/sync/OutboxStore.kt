package app.shoalwatch.data.sync

import androidx.room.withTransaction
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.db.sync.OutboxEntity
import app.shoalwatch.data.db.sync.OutboxRow
import app.shoalwatch.data.db.sync.SyncMarkEntity
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.ReadHealth
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * The durable outbox (see "Sync outbox" in docs/ARCHITECTURE.md): each profile's watched-state changes, in `seq` order, with the
 * marks that tie them to the cached rows they changed. The only writer of `sync_outbox` and `sync_marks`. A row ends only when
 * [take] is called for it (the server confirmed or overruled it), when a newer change of the same kind for the same item
 * replaces it, or with its account.
 */
internal class OutboxStore(private val db: AppDatabase, private val now: () -> Long) {
    /** Marks and the lists the change took items out of, for [enqueue]. */
    class Marks(val itemIds: Collection<String>, val leftLists: Collection<Pair<String, String>>) // (itemId, listKey)

    class Landed(
        val itemId: String,
        val itemKind: ItemKind,
        val kind: ChangeKind,
        val occurredAt: Long,
        val fromOfflinePlay: Boolean,
        val unmarked: List<String>,
        val refreshKeys: List<String>,
        /** The (item, list key) departures the row carried. */
        val leftLists: List<Pair<String, String>> = emptyList(),
    ) {
        override fun toString() = "Landed(<redacted>)"
    }

    private val dao = db.outboxDao()
    private val health = ReadHealth(TAG)

    /**
     * MUST be called inside the caller's transaction (the local change and its row commit together). Adds [change] for [s] after
     * every earlier row. An unsent row for the same (profile, item, kind) is replaced (its marks go with it), except PLAY, which is
     * never coalesced (each is a separate watch). Returns the new seq.
     */
    suspend fun enqueue(s: Session, change: Change, marks: Marks, refreshKeys: Collection<String>): Long {
        val ids = listOf(s.serverId, s.userId, change.itemId) + marks.itemIds + marks.leftLists.map { it.first }
        require(ids.all(::normalised)) { "Not a normalised id." }
        require(marks.leftLists.all { it.second.isNotEmpty() }) { "A list key cannot be empty." }
        require(refreshKeys.none { it.isEmpty() || ',' in it }) { "A refresh key cannot be empty or hold a comma." }
        val joined = refreshKeys.joinToString(",")
        require(joined.length <= MAX_REFRESH_CHARS) { "Too many refresh keys." }
        return db.withTransaction {
            if (change.kind != ChangeKind.PLAY) {
                for (old in dao.sameChange(s.serverId, s.userId, change.itemId, change.kind.name)) delete(old)
            }
            val seq = dao.insert(
                OutboxEntity(
                    serverId = s.serverId, userId = s.userId, itemId = change.itemId, itemKind = change.itemKind, kind = change.kind,
                    flag = change.flag, positionMs = change.positionMs, occurredAt = change.occurredAt, refreshKeys = joined,
                    fromOfflinePlay = change.fromOfflinePlay,
                ),
            )
            dao.insertMarks(
                marks.itemIds.map { SyncMarkEntity(seq, s.serverId, s.userId, it, PLAIN) } +
                    marks.leftLists.map { (itemId, listKey) -> SyncMarkEntity(seq, s.serverId, s.userId, itemId, listKey) },
            )
            seq
        }
    }

    /**
     * The next row to send for [s]; null when nothing is due. In `seq` order: a deferred row (a 5xx, not yet stuck) holds back
     * the whole profile until it is due; a stuck row not yet due is passed over, and so is every later row for its item.
     */
    suspend fun head(s: Session, nowMs: Long): OutboxEntity? {
        val held = HashSet<String>()
        var after = 0L
        while (true) {
            val page = dao.rows(s.serverId, s.userId, after, PAGE)
            if (page.isEmpty()) return null
            for (raw in page) {
                after = raw.seq
                val row = readable(raw) ?: continue
                if (row.itemId in held) continue
                if (row.notBefore > nowMs) {
                    if (row.serverErrors < STUCK_AFTER) return null
                    held += row.itemId
                    continue
                }
                return row
            }
        }
    }

    /** Committed BEFORE the request leaves: sentOnce = 1, attempts + 1, and for PLAY the absolute count (kept if already set). */
    suspend fun markSending(seq: Long, playCount: Int?) = dao.markSending(seq, playCount)

    /** A 5xx: serverErrors + 1, notBefore = now + min(60 s × 2^(serverErrors − 1), 6 h). */
    suspend fun deferAfterServerError(seq: Long) = db.withTransaction {
        val row = dao.row(seq) ?: return@withTransaction
        val errors = row.serverErrors + 1
        val wait = (FIRST_WAIT_MS shl (errors - 1).coerceIn(0, MAX_SHIFT)).coerceAtMost(MAX_WAIT_MS)
        dao.defer(seq, errors, now() + wait)
    }

    /** In the caller's transaction: deletes the row (and its marks) and says which marks are now uncovered. Null if it is gone. */
    suspend fun take(seq: Long): Landed? = db.withTransaction {
        val raw = dao.row(seq) ?: return@withTransaction null
        val row = readable(raw) ?: return@withTransaction null
        val marks = dao.marksOf(seq)
        val marked = marks.map { it.itemId }.distinct()
        delete(seq)
        Landed(
            itemId = row.itemId, itemKind = row.itemKind, kind = row.kind, occurredAt = row.occurredAt, fromOfflinePlay = row.fromOfflinePlay,
            unmarked = marked.filterNot { dao.hasMark(row.serverId, row.userId, it) },
            refreshKeys = row.refreshKeys.split(',').filter { it.isNotEmpty() },
            leftLists = marks.filter { it.leftList != PLAIN }.map { it.itemId to it.leftList },
        )
    }

    /** True while any row for [s] marks [itemId] (plainly or as having left a list). */
    suspend fun hasMark(s: Session, itemId: String): Boolean = dao.hasMark(s.serverId, s.userId, itemId)

    /** Items an unsent change took out of [listKey] for [s] (for LibraryStore.writePage). */
    suspend fun leftList(s: Session, listKey: String): Set<String> = dao.leftList(s.serverId, s.userId, listKey).toSet()

    /** Never throws: a failed read emits [SyncStatus.NONE] (as [ReadHealth], which builds the Room flows inside its guard). */
    fun status(s: Session, signedIn: Flow<Boolean>): Flow<SyncStatus> = health.guard("the outbox", SyncStatus.NONE) {
        combine(dao.observeCounts(s.serverId, s.userId, STUCK_AFTER), dao.observeLastSynced(s.serverId, s.userId), signedIn) { c, last, isIn ->
            SyncStatus(waiting = c.waiting, stuck = c.stuck, needsSignIn = c.waiting > 0 && !isIn, lastSyncedAt = last)
        }
    }

    suspend fun waiting(s: Session): Int = dao.count(s.serverId, s.userId)

    suspend fun accountsWithWork(): List<Session> = dao.accountsWithWork().map { Session(it.serverId, it.userId) }

    /** Never throws: a failed read emits no servers. */
    fun serversWithWork(): Flow<Set<String>> = health.guard("the outbox servers", emptySet()) { dao.serversWithWork().map { it.toSet() } }

    /** sync_state.lastSyncedAt = now (nothing for an account that has gone). */
    suspend fun touchSynced(s: Session) = db.withTransaction {
        val at = now()
        dao.insertSynced(s.serverId, s.userId, at)
        dao.updateSynced(s.serverId, s.userId, at)
    }

    /** As [enqueue], unless an unsent row of the same kind for the item is as new or newer (it would replace it): then null. One transaction. */
    suspend fun enqueueUnlessNewer(s: Session, change: Change, marks: Marks, refreshKeys: Collection<String>): Long? = db.withTransaction {
        if (dao.hasSameSince(s.serverId, s.userId, change.itemId, change.kind.name, change.occurredAt)) null else enqueue(s, change, marks, refreshKeys)
    }

    /** MUST be called inside the caller's transaction: [s]'s unsent rows of [kind] for [itemId] go, with their marks (a newer value reached the server). */
    suspend fun dropSame(s: Session, itemId: String, kind: ChangeKind) = db.withTransaction {
        for (old in dao.sameChange(s.serverId, s.userId, itemId, kind.name)) delete(old)
    }

    private suspend fun delete(seq: Long) {
        dao.deleteMarks(seq)
        dao.deleteRow(seq)
    }

    /** The row as an entity, or null after deleting it: a kind this version cannot read can never be sent. */
    private suspend fun readable(raw: OutboxRow): OutboxEntity? {
        val kind = ChangeKind.entries.firstOrNull { it.name == raw.kind }
        if (kind == null) {
            AppLog.w(TAG, "Dropped an outbox row of a kind this version cannot read")
            delete(raw.seq)
            return null
        }
        return OutboxEntity(
            seq = raw.seq, serverId = raw.serverId, userId = raw.userId, itemId = raw.itemId, itemKind = raw.itemKind, kind = kind,
            flag = raw.flag, positionMs = raw.positionMs, occurredAt = raw.occurredAt, refreshKeys = raw.refreshKeys,
            fromOfflinePlay = raw.fromOfflinePlay, sentOnce = raw.sentOnce, sentPlayCount = raw.sentPlayCount, attempts = raw.attempts,
            serverErrors = raw.serverErrors, notBefore = raw.notBefore,
        )
    }

    private fun normalised(id: String) = JellyfinId.parseOrNull(id)?.value == id

    companion object {
        const val STUCK_AFTER = 10
        const val MAX_REFRESH_CHARS = 2_000
        private const val TAG = "Outbox"
        private const val PLAIN = ""
        private const val PAGE = 50
        private const val FIRST_WAIT_MS = 60_000L
        private const val MAX_WAIT_MS = 6 * 60 * 60_000L
        private const val MAX_SHIFT = 20
    }
}
