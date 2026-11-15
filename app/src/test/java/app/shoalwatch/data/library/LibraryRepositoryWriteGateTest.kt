package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

/** Every write to the library, and the daily clear-out, takes one lock: they never overlap. */
class LibraryRepositoryWriteGateTest : LibraryRepositoryTestBase() {
    private val gate = Mutex()
    private val held by lazy { LibraryRepository(gateway, db, appScope, Dispatchers.IO, { now }, log, gate) }

    @Test fun aRefreshWriteWaitsForTheLock() = runBlocking {
        gateway.resumeFor = { films("a") }
        gate.withLock {
            val collector = launch { held.home(s1u1).collect { } }
            until { gateway.count("resume") == 1 } // the answer is in, and now the write has to wait
            delay(150) // a negative check: long enough for an unguarded write to have finished
            assertEquals(0, count("list_state", "listKey = 'resume'"))
            gateway.gate = null
            collector.cancel()
        }
        settle()
        assertEquals(1, count("list_state", "listKey = 'resume'"))
    }

    @Test fun theClearOutWaitsForTheLockToo() = runBlocking {
        now = t0.toEpochMilli() + Duration.ofDays(40).toMillis()
        library.writePage(s1u1, "old-list", 0, films("x"), 1, t0)
        gateway.failAll = AppError.Kind.OFFLINE
        gate.withLock {
            held.home(s1u1).first5s()
            delay(150)
            assertEquals(1, count("list_entries", "listKey = 'old-list'"))
        }
        settle()
        assertEquals(0, count("list_entries", "listKey = 'old-list'"))
    }
}
