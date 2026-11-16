package app.shoalwatch.data.sync

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.api.SyncGateway
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [SyncGateway] that behaves like a server holding one copy per (session, item). Every call is logged with its session in
 * [calls]; it can be held at [gate], made to fail ([failNext], per operation), or made to apply and then fail ([applyThenFail]),
 * which is a lost reply. A copy that was never set reads as "not played, count 0".
 */
internal class FakeSyncGateway : SyncGateway {
    data class Call(val session: Session, val op: String, val itemId: String, val body: UserDataUpdate? = null)

    val calls = CopyOnWriteArrayList<Call>()
    val copies = ConcurrentHashMap<Pair<Session, String>, ServerUserData>()

    /** Items for which userData answers "unknown" (a server without the route). */
    val noRoute: MutableSet<Pair<Session, String>> = ConcurrentHashMap.newKeySet()

    /** itemGone's answer per item (default false). */
    val gone: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Every call waits here first, if set. */
    @Volatile var gate: CompletableDeferred<Unit>? = null

    /** Calls that have arrived at [gate] (so a test knows how many are held). */
    val arrivals = AtomicInteger()

    /** The next calls of an operation ("userData", "update", ...; "*" for any) fail with these kinds, in turn. */
    private val failures = ConcurrentHashMap<String, ArrayDeque<AppError.Kind>>()

    /** The next calls of an operation are applied to the copy and then fail with this kind (the reply was lost). */
    private val lostReplies = ConcurrentHashMap<String, ArrayDeque<AppError.Kind>>()

    /** Runs in every call after it is logged and released. */
    @Volatile var onCall: suspend (Call) -> Unit = {}

    fun failNext(op: String, kind: AppError.Kind, times: Int = 1) = synchronized(failures) {
        failures.getOrPut(op) { ArrayDeque() }.apply { repeat(times) { addLast(kind) } }
    }

    fun applyThenFail(op: String, kind: AppError.Kind) = synchronized(lostReplies) { lostReplies.getOrPut(op) { ArrayDeque() }.addLast(kind) }

    fun copy(s: Session, itemId: String): ServerUserData = copies[s to itemId] ?: ServerUserData(false, 0, 0, false, null)

    fun ops(): List<String> = calls.map { it.op }

    override suspend fun userData(s: Session, itemId: String): ServerUserData? {
        call(Call(s, "userData", itemId))
        return if (s to itemId in noRoute) null else copy(s, itemId)
    }

    override suspend fun update(s: Session, itemId: String, body: UserDataUpdate) = call(Call(s, "update", itemId, body)) {
        val old = copy(s, itemId)
        copies[s to itemId] = old.copy(
            played = body.played ?: old.played,
            playCount = body.playCount ?: old.playCount,
            positionMs = body.positionMs ?: old.positionMs,
            lastPlayedAt = body.lastPlayedAt ?: old.lastPlayedAt,
        )
    }

    override suspend fun markPlayed(s: Session, itemId: String) = call(Call(s, "markPlayed", itemId)) {
        val old = copy(s, itemId)
        copies[s to itemId] = old.copy(played = true, playCount = maxOf(old.playCount ?: 0, 1), positionMs = 0)
    }

    override suspend fun markUnplayed(s: Session, itemId: String) = call(Call(s, "markUnplayed", itemId)) {
        copies[s to itemId] = copy(s, itemId).copy(played = false, playCount = 0, lastPlayedAt = null)
    }

    override suspend fun favourite(s: Session, itemId: String, on: Boolean) = call(Call(s, if (on) "favourite" else "unfavourite", itemId)) {
        copies[s to itemId] = copy(s, itemId).copy(favourite = on)
    }

    override suspend fun itemGone(s: Session, itemId: String): Boolean {
        call(Call(s, "itemGone", itemId))
        return itemId in gone
    }

    private suspend fun call(call: Call, apply: () -> Unit = {}) {
        calls += call
        gate?.let { arrivals.incrementAndGet(); it.await() }
        onCall(call)
        take(failures, call.op)?.let { throw AppError.of(it) }
        apply()
        take(lostReplies, call.op)?.let { throw AppError.of(it) }
    }

    private fun take(from: ConcurrentHashMap<String, ArrayDeque<AppError.Kind>>, op: String): AppError.Kind? = synchronized(from) {
        from[op]?.removeFirstOrNull() ?: from["*"]?.removeFirstOrNull()
    }
}
