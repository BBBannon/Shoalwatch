package app.shoalwatch.data.sync.api

import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.ServerApiTestBase
import app.shoalwatch.data.library.api.LibraryGatewayImpl
import app.shoalwatch.data.sync.ServerUserData
import app.shoalwatch.testId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.shadows.ShadowLog

/** The server's copy is untrusted: bounded, range-checked, and never echoed into a message or a log line. */
class SyncGatewayHostileTest : ServerApiTestBase() {
    private val item = testId("film")
    private val gateway by lazy { SyncGatewayImpl(api, LibraryGatewayImpl(api)) }
    private val errors = mutableListOf<AppError>()

    private fun read(body: String): ServerUserData? {
        web.enqueue(json(body))
        return runBlocking { gateway.userData(session, item) }
    }

    private fun refused(body: String): AppError.Kind {
        web.enqueue(json(body))
        return failure { runBlocking { gateway.userData(session, item) } }.also { errors += it }.kind
    }

    @Test fun aBodyOver64KiBIsRefused() {
        assertEquals(AppError.Kind.SERVER_ERROR, refused("""{"Played":true,"Pad":"${"SECRET".repeat(65 * 1024 / 6 + 1)}"}"""))
    }

    @Test fun countsOutOfRangeAreUnknown() {
        assertNull(read("""{"PlayCount":-1}""")!!.playCount)
        assertNull(read("""{"PlayCount":1000000000}""")!!.playCount)
        assertEquals(100_000, read("""{"PlayCount":100000}""")!!.playCount)
        assertNull(read("""{"PlayCount":100001}""")!!.playCount)
        assertNull("not sent", read("""{}""")!!.playCount)
    }

    @Test fun positionsOutOfRangeAreZero() {
        assertEquals(0L, read("""{"PlaybackPositionTicks":-5}""")!!.positionMs)
        assertEquals(0L, read("""{"PlaybackPositionTicks":${Long.MAX_VALUE}}""")!!.positionMs)
        assertEquals((Long.MAX_VALUE / 10_000) / 10_000, read("""{"PlaybackPositionTicks":${Long.MAX_VALUE / 10_000}}""")!!.positionMs)
    }

    @Test fun datesThatCannotBeTrustedAreUnknown() {
        for (date in listOf("yesterday", "1700-01-01T00:00:00Z", "2300-01-01T00:00:00Z", "1999-12-31T23:59:59Z", "2200-01-01T00:00:01Z")) {
            assertNull(date, read("""{"Played":true,"LastPlayedDate":"$date"}""")!!.lastPlayedAt)
        }
        assertEquals(946_684_800_000L, read("""{"LastPlayedDate":"2000-01-01T00:00:00Z"}""")!!.lastPlayedAt)
    }

    @Test fun aWronglyTypedFieldIsAServerError() {
        assertEquals(AppError.Kind.SERVER_ERROR, refused("""{"Played":"yes-SECRET"}"""))
        // The shared decoder reads a quoted "true" as true (kotlinx); any other string is refused.
        assertEquals(true, read("""{"Played":"true"}""")!!.played)
        assertEquals(AppError.Kind.SERVER_ERROR, refused("""{"PlayCount":"SECRET"}"""))
        assertEquals(AppError.Kind.SERVER_ERROR, refused("""["SECRET"]"""))
    }

    @Test fun nothingFromTheBodyReachesAMessageOrALogLine() {
        ShadowLog.clear()
        refused("""{"Played":"yes-SECRET"}""")
        refused("""{"Played":true,"Pad":"${"SECRET".repeat(12_000)}"}""")
        read("""{"Played":true,"PlayCount":-1,"LastPlayedDate":"SECRET-yesterday"}""")
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.message}" }
        val messages = errors.joinToString("\n") { "${it.message} ${it.cause?.message}" }
        for (secret in listOf("SECRET", item)) {
            assertFalse("logged $secret", secret in logged)
            assertFalse("in a message: $secret", secret in messages)
        }
    }
}
