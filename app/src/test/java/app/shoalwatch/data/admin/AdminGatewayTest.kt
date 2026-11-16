package app.shoalwatch.data.admin

import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.JsonBodies
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every row of the endpoint table: method, path, query, body, the token only in Authorization, and the answer mapped. */
class AdminGatewayTest : AdminGatewayTestBase() {
    private fun RecordedRequest.check(method: String, path: String, query: Map<String, String> = emptyMap()) {
        val url = requestUrl!!
        assertEquals(method, this.method)
        assertEquals(path, url.encodedPath)
        assertEquals(query, url.queryParameterNames.associateWith { url.queryParameter(it)!! })
        assertTrue("the token goes in Authorization", getHeader("Authorization")!!.contains("Token=\"$TOKEN\""))
        assertFalse(url.queryParameterNames.any { it.lowercase() in setOf("api_key", "apikey", "token") })
        assertFalse(url.toString().contains(TOKEN))
    }

    private fun RecordedRequest.jsonBody(): JsonObject = JsonBodies.JSON.parseToJsonElement(body.readUtf8()).jsonObject

    @Test fun me() {
        web.enqueue(json(AdminFixtures.array("users.json")[1].toString()))
        val me = runBlocking { gateway.me(session) }
        nextRequest().check("GET", "/Users/Me")
        assertEquals(AdminMe(AdminFixtures.BEN, isAdministrator = true, isDisabled = false), me)
    }

    @Test fun serverStatus() {
        web.enqueue(fixture("system-info.json"))
        val status = runBlocking { gateway.serverStatus(session) }
        nextRequest().check("GET", "/System/Info")
        assertEquals(ServerStatus("Test Jellyfin", "12.4.0", pendingRestart = true, shuttingDown = false), status)
    }

    @Test fun sessions() {
        web.enqueue(fixture("sessions.json"))
        val sessions = runBlocking { gateway.sessions(session) }
        nextRequest().check("GET", "/Sessions", mapOf("activeWithinSeconds" to "960"))
        assertEquals(4, sessions.size)
        assertTrue(sessions.single { it.deviceName == "Pixel 8" }.isThisDevice)
    }

    @Test fun sendMessage() {
        web.enqueue(noContent())
        runBlocking { gateway.sendMessage(session, SESSION_ID, "Message from ben", "  Bed​ time\n now ") }
        val request = nextRequest()
        request.check("POST", "/Sessions/$SESSION_ID/Message")
        val body = request.jsonBody()
        assertEquals(setOf("Header", "Text", "TimeoutMs"), body.keys)
        assertEquals(JsonPrimitive("Message from ben"), body["Header"])
        assertEquals(JsonPrimitive("Bed time now"), body["Text"])
        assertEquals(JsonPrimitive(10_000), body["TimeoutMs"])
    }

    @Test fun messageTextIsCappedAt200() {
        web.enqueue(noContent())
        runBlocking { gateway.sendMessage(session, SESSION_ID, "h", "x".repeat(500)) }
        assertEquals(200, (nextRequest().jsonBody()["Text"] as JsonPrimitive).content.length)
    }

    @Test fun pauseAndStop() {
        web.enqueue(noContent())
        web.enqueue(noContent())
        runBlocking {
            gateway.command(session, SESSION_ID, RemoteCommand.PAUSE)
            gateway.command(session, SESSION_ID, RemoteCommand.STOP)
        }
        nextRequest().check("POST", "/Sessions/$SESSION_ID/Playing/Pause")
        nextRequest().check("POST", "/Sessions/$SESSION_ID/Playing/Stop")
    }

    @Test fun libraries() {
        web.enqueue(fixture("virtual-folders.json"))
        val libraries = runBlocking { gateway.libraries(session) }
        nextRequest().check("GET", "/Library/VirtualFolders")
        assertEquals(LibraryKind.TV, libraries.single { it.name == "TV" }.kind)
    }

    @Test fun scanAllAndOne() {
        web.enqueue(noContent())
        web.enqueue(noContent())
        runBlocking {
            gateway.scanAll(session)
            gateway.scanLibrary(session, LIBRARY)
        }
        nextRequest().check("POST", "/Library/Refresh")
        nextRequest().check(
            "POST", "/Items/$LIBRARY/Refresh",
            mapOf(
                "metadataRefreshMode" to "Default", "imageRefreshMode" to "Default", "replaceAllMetadata" to "false",
                "replaceAllImages" to "false", "regenerateTrickplay" to "false",
            ),
        )
    }

    @Test fun tasks() {
        web.enqueue(fixture("tasks.json"))
        val tasks = runBlocking { gateway.tasks(session) }
        nextRequest().check("GET", "/ScheduledTasks", mapOf("isHidden" to "false"))
        assertEquals(TaskState.RUNNING, tasks.single { it.key == FULL_SCAN_TASK_KEY }.state)
    }

    @Test fun startAndStopTask() {
        web.enqueue(noContent())
        web.enqueue(noContent())
        runBlocking {
            gateway.startTask(session, TASK)
            gateway.stopTask(session, TASK)
        }
        nextRequest().check("POST", "/ScheduledTasks/Running/$TASK")
        nextRequest().check("DELETE", "/ScheduledTasks/Running/$TASK")
    }

    @Test fun users() {
        web.enqueue(fixture("users.json"))
        val users = runBlocking { gateway.users(session) }
        nextRequest().check("GET", "/Users")
        assertEquals(4, users.size)
    }

    @Test fun disablingAUserPostsBackItsOwnPolicy() {
        web.enqueue(fixture("user-policy.json"))
        web.enqueue(noContent())
        runBlocking { gateway.setUserDisabled(session, AdminFixtures.KID, disabled = true) }
        nextRequest().check("GET", "/Users/${AdminFixtures.KID}")
        val post = nextRequest()
        post.check("POST", "/Users/${AdminFixtures.KID}/Policy")
        val policy = JsonBodies.JSON.parseToJsonElement(AdminFixtures.text("user-policy.json")).jsonObject.getValue("Policy").jsonObject
        assertEquals(JsonObject(policy + ("IsDisabled" to JsonPrimitive(true))), post.jsonBody())
    }

    @Test fun activity() {
        web.enqueue(fixture("activity.json"))
        val page = runBlocking { gateway.activity(session, startIndex = 50, limit = 50) }
        nextRequest().check("GET", "/System/ActivityLog/Entries", mapOf("startIndex" to "50", "limit" to "50"))
        assertEquals(59, page.nextStartIndex)
        assertEquals(Severity.CRITICAL, page.entries.single { it.id == 116L }.severity)
    }

    @Test fun devices() {
        web.enqueue(fixture("devices.json"))
        val devices = runBlocking { gateway.devices(session) }
        nextRequest().check("GET", "/Devices")
        assertEquals(listOf("ben", "kid"), devices.single { it.isThisDevice }.userNames)
    }

    @Test fun removeDevice() {
        web.enqueue(noContent())
        runBlocking { gateway.removeDevice(session, "web-ben-laptop") }
        nextRequest().check("DELETE", "/Devices", mapOf("id" to "web-ben-laptop"))
    }

    @Test fun aDeviceIdWithPathCharactersIsOnlyAQueryValue() {
        web.enqueue(noContent())
        runBlocking { gateway.removeDevice(session, "../a/b") }
        val request = nextRequest()
        request.check("DELETE", "/Devices", mapOf("id" to "../a/b"))
        assertEquals("id=..%2Fa%2Fb", request.requestUrl!!.encodedQuery)
        // A query or list separator is outside the removable set: refused with nothing sent.
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.removeDevice(session, "a/b?c") }.kind)
        assertEquals(1, web.requestCount)
    }

    @Test fun restartAndShutDown() {
        web.enqueue(noContent())
        web.enqueue(noContent())
        runBlocking {
            gateway.restart(session)
            gateway.shutDown(session)
        }
        nextRequest().check("POST", "/System/Restart")
        nextRequest().check("POST", "/System/Shutdown")
    }
}
