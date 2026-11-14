package app.shoalwatch.data

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.JellyfinGatewayImpl
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerNet
import app.shoalwatch.data.servers.ServerNetDraft
import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.ServerAddress
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S11 at the gateway boundary: ids from the server must be GUIDs and are stored in one canonical form
 * (lower-case, no dashes), and display strings from the server cannot spoof the UI.
 */
class GatewayBoundaryTest {
    private val web = MockWebServer().apply { start() }
    private val device = DeviceIdentity("device-id-1", "Pixel 9", "Shoalwatch", "0.1")
    private val gateway = JellyfinGatewayImpl(
        ServerClients(HttpClientFactory(HandshakeCertificates.Builder().build().trustManager, { error("none") })),
    )
    private val address = ServerAddress.parse("http://127.0.0.1:${web.port}")
    private val net = ServerNet.anonymous(listOf(address), ServerNetDraft(allowCleartext = true), device)

    @After fun stop() = web.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun publicInfo(id: String, name: String = "Den", version: String = "10.10.3") = runBlocking {
        web.enqueue(json("""{"Id":"$id","ServerName":"$name","Version":"$version"}"""))
        gateway.publicInfo(address, net)
    }

    private fun failure(block: suspend () -> Unit): AppError {
        try {
            runBlocking { block() }
        } catch (e: AppError) {
            return e
        }
        fail("expected an AppError")
        error("unreachable")
    }

    @Test fun dashedAndUpperCaseServerIdsNormaliseToOneKey() {
        val dashed = publicInfo("0123ABCD-4567-89EF-0123-456789ABCDEF")
        val plain = publicInfo("0123abcd456789ef0123456789abcdef")
        assertEquals("0123abcd456789ef0123456789abcdef", dashed.id)
        assertEquals("the same server can never become two rows", plain.id, dashed.id)
    }

    @Test fun aServerIdThatIsNotAGuidIsRejected() {
        for (bad in listOf("srv1", "../../etc", "0123abcd456789ef0123456789abcdeg", "0123abcd-456789ef-0123-456789abcdef", "{0123abcd456789ef0123456789abcdef}")) {
            val e = failure { publicInfo(bad) }
            assertEquals(bad, AppError.Kind.SERVER_UNSUPPORTED, e.kind)
            assertFalse(e.message!!.contains(bad))
        }
    }

    @Test fun signInIdsAreValidatedAndNormalisedToo() {
        web.enqueue(json("""{"AccessToken":"tok","ServerId":"0123ABCD-4567-89EF-0123-456789ABCDEF","User":{"Id":"FEDCBA98-7654-3210-FEDC-BA9876543210","Name":"ben"}}"""))
        val result = runBlocking { gateway.authenticateByName(address, net, "ben", "pw") }
        assertEquals("0123abcd456789ef0123456789abcdef", result.serverId)
        assertEquals("fedcba9876543210fedcba9876543210", result.userId)

        web.enqueue(json("""{"AccessToken":"tok","ServerId":"0123abcd456789ef0123456789abcdef","User":{"Id":"not-a-guid","Name":"ben"}}"""))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { gateway.authenticateByName(address, net, "ben", "pw") }.kind)
    }

    @Test fun displayStringsLoseBidiAndControlCharactersAndAreCapped() {
        val info = publicInfo("0123abcd456789ef0123456789abcdef", name = "\\u202EDen\\u0007  Media\\n\\t Server\\u2066", version = "10.10.3\\u202E")
        assertEquals("Den Media Server", info.name)
        assertEquals("10.10.3", info.version)

        val long = publicInfo("0123abcd456789ef0123456789abcdef", name = "x".repeat(500), version = "1".repeat(500))
        assertEquals(80, long.name.length)
        assertEquals(40, long.version.length)

        web.enqueue(json("""{"AccessToken":"tok","ServerId":"0123abcd456789ef0123456789abcdef","User":{"Id":"fedcba9876543210fedcba9876543210","Name":"‮nib‏${"y".repeat(200)}"}}"""))
        val user = runBlocking { gateway.authenticateByName(address, net, "ben", "pw") }.userName
        assertTrue(user.startsWith("nib"))
        assertEquals(80, user.length)
    }

    @Test fun aNameThatIsOnlyInvisibleCharactersFallsBack() {
        assertEquals("Jellyfin", publicInfo("0123abcd456789ef0123456789abcdef", name = "\\u202E\\u200B ").name)
    }

    @Test fun aUserNameThatIsOnlyInvisibleCharactersFallsBack() {
        web.enqueue(json("""{"AccessToken":"tok","ServerId":"0123abcd456789ef0123456789abcdef","User":{"Id":"fedcba9876543210fedcba9876543210","Name":"‮⁣​"}}"""))
        assertEquals("Unnamed user", runBlocking { gateway.authenticateByName(address, net, "ben", "pw") }.userName)
    }

    // ---- the Quick Connect code is shown on screen, so it is checked here (review F6)

    private fun initiate(code: String) = runBlocking {
        web.enqueue(json("""{"Secret":"sec-1","Code":"$code","Authenticated":false}"""))
        gateway.quickConnectInitiate(address, net)
    }

    @Test fun aQuickConnectCodeOfFourToTwelveAsciiLettersAndDigitsIsAccepted() {
        for (ok in listOf("1234", "AB12cd", "123456789012")) assertEquals(ok, initiate(ok).code)
    }

    @Test fun anyOtherQuickConnectCodeIsAServerErrorAndIsNeverEchoed() {
        val bad = listOf("123", "1234567890123", "12 34", "12-34", "١٢٣٤", "ab‮cd", "<b>12</b>", "12\n34")
        for (code in bad) {
            val e = failure { initiate(code) }
            assertEquals(code, AppError.Kind.SERVER_ERROR, e.kind)
            assertFalse(e.message!!.contains(code))
        }
    }
}
