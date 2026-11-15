package app.shoalwatch.data.library

import app.cash.turbine.ReceiveTurbine
import app.shoalwatch.data.db.LibraryTestBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import java.time.Duration

/** A [MaintenanceLog] that lives in memory. */
class MemoryLog(var last: Long? = null) : MaintenanceLog {
    val records = mutableListOf<Long>()
    override fun lastRun() = last
    override fun record(atMs: Long) { last = atMs; records += atMs }
}

/**
 * The real [LibraryRepository] on a real in-memory Room and a [FakeLibraryGateway]. Time is virtual: [now] is the
 * repository's clock and only moves when a test moves it. The repository's scope is the stand-in for the app scope.
 */
abstract class LibraryRepositoryTestBase : LibraryTestBase() {
    protected val gateway = FakeLibraryGateway()
    protected val log = MemoryLog()
    protected var now: Long = t0.toEpochMilli()
    protected val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    protected lateinit var repo: LibraryRepository

    @Before fun openRepository() {
        repo = LibraryRepository(gateway, db, appScope, Dispatchers.IO, { now }, log)
    }

    /** Lets background work finish before the database is closed (this @After runs before the base class's). */
    @After fun stopRepository() = runBlocking {
        gateway.gate?.complete(Unit)
        appScope.coroutineContext.job.children.forEach { it.join() }
        appScope.cancel()
    }

    /** Waits until everything the repository started in the background (refreshes, clear-outs) has finished. Gate-held work must be released first. */
    protected suspend fun settle() {
        while (true) {
            val running = appScope.coroutineContext.job.children.toList()
            if (running.isEmpty()) return
            running.forEach { it.join() }
        }
    }

    protected fun advance(duration: Duration) { now += duration.toMillis() }

    /** The first value of this flow that satisfies [test]; fails after five real seconds rather than hanging. */
    protected suspend fun <T> Flow<T>.first5s(test: (T) -> Boolean = { true }): T = withTimeout(5_000) { first(test) }

    /** Items from a Turbine until one satisfies [test]. */
    protected suspend fun <T> ReceiveTurbine<T>.awaitUntil(test: (T) -> Boolean): T {
        while (true) awaitItem().let { if (test(it)) return it }
    }

    protected fun <T> Flow<T>.await(test: (T) -> Boolean = { true }): T = runBlocking { first5s(test) }

    /** Waits (for real, briefly) until [condition] holds, e.g. for a call to reach the held gateway. */
    protected suspend fun until(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }

    protected fun view(label: String, kind: LibraryKind = LibraryKind.MOVIES) = LibraryView(id(label), "Lib $label", kind)

    protected fun films(vararg labels: String) = labels.map { row(item(it)) }

    protected fun names(rows: List<ItemWithUserData>) = rows.map { it.item.name }

    protected val nameAsc = Sort(SortBy.NAME, descending = false)
    protected val noFilter = ItemFilter()
}
