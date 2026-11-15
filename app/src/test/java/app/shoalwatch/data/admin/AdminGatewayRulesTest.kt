package app.shoalwatch.data.admin

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.api.UserPolicyEdit
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowLog

/** The gate, id checks, never retrying an action, status mapping, the policy edit's refusals, and what may be logged. */
class AdminGatewayRulesTest : AdminGatewayTestBase() {
    private val reads: List<Pair<String, suspend () -> Unit>> = listOf(
        "serverStatus" to { gateway.serverStatus(session) },
        "sessions" to { gateway.sessions(session) },
        "libraries" to { gateway.libraries(session) },
        "tasks" to { gateway.tasks(session) },
        "users" to { gateway.users(session) },
        "activity" to { gateway.activity(session, 0, 50) },
        "devices" to { gateway.devices(session) },
    )
    private val actions: List<Pair<String, suspend () -> Unit>> = listOf(
        "sendMessage" to { gateway.sendMessage(session, SESSION_ID, "Message from ben", "hi") },
        "command" to { gateway.command(session, SESSION_ID, RemoteCommand.STOP) },
        "scanAll" to { gateway.scanAll(session) },
        "scanLibrary" to { gateway.scanLibrary(session, LIBRARY) },
        "startTask" to { gateway.startTask(session, TASK) },
        "stopTask" to { gateway.stopTask(session, TASK) },
        "setUserDisabled" to { gateway.setUserDisabled(session, AdminFixtures.KID, true) },
        "removeDevice" to { gateway.removeDevice(session, "web-ben-laptop") },
        "restart" to { gateway.restart(session) },
        "shutDown" to { gateway.shutDown(session) },
    )

    @Test fun withoutAdminRightsEveryCallIsRefusedAndNothingIsSent() {
        allowed = false
        assertEquals(17, reads.size + actions.size)
        for ((name, call) in reads + actions) assertEquals(name, AppError.Kind.FORBIDDEN, failure(call).kind)
        assertEquals(0, web.requestCount)
    }

    @Test fun anotherSessionIsRefusedToo() {
        val other = Session(S1, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        assertEquals(AppError.Kind.FORBIDDEN, failure { gateway.restart(other) }.kind)
        assertEquals(0, web.requestCount)
    }

    @Test fun malformedIdsAreNotFoundAndNothingIsSent() {
        val bad: List<suspend () -> Unit> = listOf(
            { gateway.sendMessage(session, "../x", "h", "t") },
            { gateway.command(session, "a".repeat(65), RemoteCommand.PAUSE) },
            { gateway.command(session, "", RemoteCommand.PAUSE) },
            { gateway.scanLibrary(session, "../x") },
            { gateway.scanLibrary(session, LIBRARY.uppercase()) },
            { gateway.startTask(session, "../x") },
            { gateway.stopTask(session, "a b") },
            { gateway.setUserDisabled(session, "1d5e1f3b-2a4c-4e6f-8a9b-0c1d2e3f4a5b", true) },
            { gateway.setUserDisabled(session, AdminFixtures.KID.uppercase(), true) },
            { gateway.removeDevice(session, "has space") },
            { gateway.removeDevice(session, "x".repeat(257)) },
            // DELETE /Devices takes `id` as an array, which the server may split on commas: this would also remove this phone.
            { gateway.removeDevice(session, "junk,${AdminFixtures.THIS_PHONE}") },
            { gateway.removeDevice(session, "junk;other") },
        )
        for (call in bad) assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure(call).kind)
        assertEquals(0, web.requestCount)
    }

    /** The screens never offer it, but a stale screen or a second caller must not either: it would sign out every account here. */
    @Test fun thisPhonesOwnDeviceIsNeverRemoved() {
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.removeDevice(session, AdminFixtures.THIS_PHONE) }.kind)
        assertEquals(0, web.requestCount)
    }

    /** Disabling the signed-in administrator would lock them out of their own server: refused before the policy is even read. */
    @Test fun theSignedInAdministratorsOwnAccountIsNeverDisabled() {
        for (disabled in listOf(true, false)) {
            assertEquals(AppError.Kind.FORBIDDEN, failure { gateway.setUserDisabled(session, session.userId, disabled) }.kind)
        }
        assertEquals(0, web.requestCount)
    }

    /** On a fresh connection (see [anActionOnAPooledConnectionIsSentOnce] for a reused one). */
    @Test fun everyActionIsSentExactlyOnceWhenTheConnectionDrops() {
        for ((name, call) in actions) {
            val before = web.requestCount
            if (name == "setUserDisabled") web.enqueue(fixture("user-policy.json").setHeader("Connection", "close"))
            web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertEquals(name, AppError.Kind.NETWORK, failure(call).kind)
            val expected = if (name == "setUserDisabled") 2 else 1
            assertEquals("$name was sent more than once", expected, web.requestCount - before)
        }
    }

    /**
     * The same rule when the action goes out on a kept-alive connection that the server then drops: OkHttp's own
     * `retryOnConnectionFailure` must not resend it (ServerApi marks a `retry = false` body one-shot).
     */
    @Test fun anActionOnAPooledConnectionIsSentOnce() {
        for ((name, call) in actions) {
            web.dispatcher = QueueDispatcher() // forget an unused answer from the previous round
            web.enqueue(fixture("system-info.json")) // keeps the connection alive in the pool
            runBlocking { gateway.serverStatus(session) }
            val before = web.requestCount
            if (name == "setUserDisabled") web.enqueue(fixture("user-policy.json"))
            web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            web.enqueue(noContent().setHeader("Connection", "close")) // what a silent resend would get: fails fast, not a timeout
            val outcome = runCatching { runBlocking { call() } }
            val expected = if (name == "setUserDisabled") 2 else 1
            assertEquals("$name was sent more than once", expected, web.requestCount - before)
            assertEquals(name, AppError.Kind.NETWORK, (outcome.exceptionOrNull() as? AppError)?.kind)
        }
    }

    /** The account stops being allowed while its policy is being read: the change is never posted. */
    @Test fun setUserDisabledSendsNothingIfTheGateClosesBetweenReadAndWrite() {
        web.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                allowed = false // the gate closed while this answer was on its way
                return fixture("user-policy.json")
            }
        }
        assertEquals(AppError.Kind.FORBIDDEN, failure { gateway.setUserDisabled(session, AdminFixtures.KID, true) }.kind)
        assertEquals("only the read went out", 1, web.requestCount)
        assertEquals("GET", web.takeRequest().method)
    }

    /**
     * The gate closes (a profile switch) while an action waits for the server's address: it is checked again just before the
     * request is written, so nothing goes out. setUserDisabled's policy read is a read; its POST is what must not be sent.
     */
    @Test fun anActionWhoseGateClosesWhileItWaitsForTheAddressIsNotSent() {
        for ((name, call) in actions) {
            allowed = true
            whileWaitingForTheAddress = { allowed = false }
            web.dispatcher = QueueDispatcher()
            if (name == "setUserDisabled") web.enqueue(fixture("user-policy.json"))
            val before = web.requestCount
            assertEquals(name, AppError.Kind.FORBIDDEN, failure(call).kind)
            val expected = if (name == "setUserDisabled") 1 else 0
            assertEquals("$name was sent after the gate closed", expected, web.requestCount - before)
        }
        repeat(web.requestCount) { assertEquals("only reads went out", "GET", web.takeRequest().method) }
    }

    @Test fun aReadIsRetriedOnce() {
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(AppError.Kind.NETWORK, failure { gateway.sessions(session) }.kind)
        assertEquals(2, web.requestCount)
    }

    @Test fun a401OnRestartSignsTheAccountOutLocally() {
        web.enqueue(MockResponse().setResponseCode(401))
        assertEquals(AppError.Kind.UNAUTHORISED, failure { gateway.restart(session) }.kind)
        assertNull(store.cachedToken(S1, U1))
    }

    @Test fun statusMapping() {
        web.enqueue(MockResponse().setResponseCode(403))
        assertEquals(AppError.Kind.FORBIDDEN, failure { gateway.tasks(session) }.kind)
        web.enqueue(MockResponse().setResponseCode(404))
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.removeDevice(session, "web-ben-laptop") }.kind)
        web.enqueue(MockResponse().setResponseCode(500))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { gateway.scanAll(session) }.kind)
    }

    @Test fun aRedirectIsNotFollowed() {
        val elsewhere = MockWebServer().apply { start() }
        try {
            web.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/System/Restart")))
            assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.restart(session) }.kind)
            assertEquals(0, elsewhere.requestCount)
        } finally {
            elsewhere.shutdown()
        }
    }

    private fun policyAnswer(edit: (JsonObject) -> JsonObject) =
        json(edit(kotlinx.serialization.json.Json.parseToJsonElement(AdminFixtures.text("user-policy.json")).jsonObject).toString())

    @Test fun anUnusableUserAnswerPostsNothing() {
        val answers = listOf(
            policyAnswer { JsonObject(it + ("Id" to JsonPrimitive(AdminFixtures.BEN))) },
            policyAnswer { JsonObject(it - "Policy") },
            policyAnswer { JsonObject(it + ("Policy" to JsonObject(it.getValue("Policy").jsonObject - "PasswordResetProviderId"))) },
            json("[]"),
        )
        for (answer in answers) {
            val before = web.requestCount
            web.enqueue(answer)
            assertEquals(AppError.Kind.SERVER_ERROR, failure { gateway.setUserDisabled(session, AdminFixtures.KID, true) }.kind)
            assertEquals("only the GET was sent", 1, web.requestCount - before)
        }
    }

    @Test fun aPolicyAnswerOver256KiBPostsNothing() {
        val big = policyAnswer { JsonObject(it + ("Padding" to JsonPrimitive("x".repeat(UserPolicyEdit.MAX_POLICY_BYTES.toInt())))) }
        web.enqueue(big)
        assertEquals(AppError.Kind.SERVER_ERROR, failure { gateway.setUserDisabled(session, AdminFixtures.KID, true) }.kind)
        assertEquals(1, web.requestCount)
    }

    @Test fun programmerErrorsSendNothing() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { gateway.activity(session, 0, 0) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { gateway.activity(session, 0, 101) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { gateway.activity(session, -1, 50) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { gateway.sendMessage(session, SESSION_ID, "h", " ​‮ ") } }
        assertEquals(0, web.requestCount)
    }

    @Test fun logsHoldNoNameIdPathTextOrToken() {
        ShadowLog.clear()
        for (name in listOf("system-info.json", "sessions.json", "virtual-folders.json", "tasks.json", "users.json", "activity.json", "devices.json")) {
            web.enqueue(fixture(name))
        }
        runBlocking {
            gateway.serverStatus(session); gateway.sessions(session); gateway.libraries(session); gateway.tasks(session)
            gateway.users(session); gateway.activity(session, 0, 50); gateway.devices(session)
        }
        for (code in listOf(403, 404, 500)) {
            web.enqueue(MockResponse().setResponseCode(code).setHeader("Connection", "close"))
            failure { gateway.sendMessage(session, SESSION_ID, "Message from ben", "Harbour Lights bed time") }
        }
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        failure { gateway.removeDevice(session, "web-ben-laptop") }
        allowed = false
        failure { gateway.devices(session) }
        val logs = ShadowLog.getLogs().map { "${it.tag} ${it.msg}" }
        assertTrue("admin failures are logged by kind", logs.any { it.startsWith("Admin ") && it.contains("FORBIDDEN") })
        val secrets = listOf(
            TOKEN, "MOCKDEVICETOKEN", "Living room TV", "Harbour Lights", "Test Jellyfin", "web-ben-laptop", SESSION_ID, AdminFixtures.KID,
            "/Sessions", "/Devices", "192.168.", "ffmpeg", "bed time",
        )
        for (line in logs) for (secret in secrets) assertFalse("a log line holds a secret", line.contains(secret))
    }
}
