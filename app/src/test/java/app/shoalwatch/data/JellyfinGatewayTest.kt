package app.shoalwatch.data

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.JellyfinGatewayImpl
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerInfo
import app.shoalwatch.data.servers.ServerNet
import app.shoalwatch.data.servers.ServerNetDraft
import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.ServerNetConfig
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
import java.util.concurrent.atomic.AtomicReference

class JellyfinGatewayTest {
    private val server = MockWebServer()
    private val device = DeviceIdentity("device-id-1", "Pixel 9", "Shoalwatch", "0.1")
    private val noTrust = HandshakeCertificates.Builder().build().trustManager
    private val gateway = JellyfinGatewayImpl(ServerClients(HttpClientFactory(noTrust, { error("no client certificates here") })))

    @After fun stop() = server.shutdown()

    private fun address(path: String = ""): ServerAddress = ServerAddress.parse("http://127.0.0.1:${server.port}$path")

    private fun net(a: ServerAddress = address(), cleartext: Boolean = true): ServerNetConfig =
        ServerNet.anonymous(listOf(a), ServerNetDraft(allowCleartext = cleartext), device)

    private fun signedInNet(a: ServerAddress, token: () -> String?): ServerNetConfig = ServerNet.authenticated(
        listOf(a), Server("s1", "Den", "10.10.3", a, null, true, null, null, emptyMap()), "u1", device,
    ) { _, _ -> token() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun kindOf(block: suspend () -> Unit): AppError {
        try {
            runBlocking { block() }
        } catch (e: AppError) {
            return e
        }
        fail("expected an AppError")
        error("unreachable")
    }

    private val authBody = """{"AccessToken":"tok-XYZ","ServerId":"srv1","User":{"Id":"user1","Name":"ben","Extra":1},"Other":true}"""

    @Test fun publicInfoParsesAndSendsAnonymousAuthorizationHeader() {
        server.start(); server.enqueue(json("""{"Id":"srv1","ServerName":"Den","Version":"10.10.3","Unknown":[1]}"""))
        val info = runBlocking { gateway.publicInfo(address(), net()) }

        assertEquals(ServerInfo("srv1", "Den", "10.10.3", address(), ServerInfo.Compatibility.SUPPORTED), info)
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/System/Info/Public", r.path)
        assertEquals(
            "MediaBrowser Client=\"Shoalwatch\", Device=\"Pixel 9\", DeviceId=\"device-id-1\", Version=\"0.1\"",
            r.getHeader("Authorization"),
        )
    }

    @Test fun publicInfoHonoursABasePath() {
        server.start(); server.enqueue(json("""{"Id":"a","ServerName":"x","Version":"10.10.0"}"""))
        runBlocking { gateway.publicInfo(address("/jellyfin"), net(address("/jellyfin"))) }
        assertEquals("/jellyfin/System/Info/Public", server.takeRequest().path)
    }

    @Test fun blankServerNameFallsBackAndMissingVersionIsUnknown() {
        server.start(); server.enqueue(json("""{"Id":"a","ServerName":" "}"""))
        val info = runBlocking { gateway.publicInfo(address(), net()) }
        assertEquals("Jellyfin", info.name)
        assertEquals(ServerInfo.Compatibility.UNKNOWN, info.compatibility)
    }

    @Test fun somethingThatIsNotJellyfinIsServerNotFound() {
        server.start()
        server.enqueue(MockResponse().setBody("<html>router login</html>"))
        server.enqueue(json("""{"ServerName":"no id"}"""))
        server.enqueue(json("{}", 404))
        repeat(3) { assertEquals(AppError.Kind.SERVER_NOT_FOUND, kindOf { gateway.publicInfo(address(), net()) }.kind) }
    }

    @Test fun serverErrorMapsFromStatus() {
        server.start(); server.enqueue(json("{}", 503))
        assertEquals(AppError.Kind.SERVER_ERROR, kindOf { gateway.publicInfo(address(), net()) }.kind)
    }

    @Test fun oversizedResponseIsRejected() {
        server.start(); server.enqueue(json("""{"Id":"a","ServerName":"${"x".repeat(1_100_000)}","Version":"10.10.0"}"""))
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, kindOf { gateway.publicInfo(address(), net()) }.kind)
    }

    @Test fun quickConnectEnabledReadsBareBoolean() {
        server.start(); server.enqueue(json("true")); server.enqueue(json("false"))
        assertTrue(runBlocking { gateway.quickConnectEnabled(address(), net()) })
        assertFalse(runBlocking { gateway.quickConnectEnabled(address(), net()) })
        assertEquals("/QuickConnect/Enabled", server.takeRequest().path)
    }

    @Test fun quickConnectInitiatePostsAndParsesSecretAndCode() {
        server.start(); server.enqueue(json("""{"Secret":"sec-123","Code":"482913","Authenticated":false}"""))
        val s = runBlocking { gateway.quickConnectInitiate(address(), net()) }
        assertEquals("482913", s.code)
        assertEquals("sec-123", s.secret)
        assertFalse(s.toString().contains("sec-123"))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/QuickConnect/Initiate", r.path)
    }

    @Test fun quickConnectStatePollsWithSecretInQuery() {
        server.start(); server.enqueue(json("""{"Authenticated":true}""")); server.enqueue(json("""{"Authenticated":false}"""))
        assertTrue(runBlocking { gateway.quickConnectState(address(), net(), "sec 1&x") })
        assertFalse(runBlocking { gateway.quickConnectState(address(), net(), "sec-2") })
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/QuickConnect/Connect?secret=sec%201%26x", r.path)
    }

    @Test fun authenticateWithQuickConnectPostsSecretBodyAndParsesResult() {
        server.start(); server.enqueue(json(authBody))
        val result = runBlocking { gateway.authenticateWithQuickConnect(address(), net(), "sec-123") }
        assertEquals("user1", result.userId); assertEquals("ben", result.userName)
        assertEquals("tok-XYZ", result.accessToken); assertEquals("srv1", result.serverId)
        assertFalse(result.toString().contains("tok-XYZ"))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/Users/AuthenticateWithQuickConnect", r.path)
        assertEquals("""{"Secret":"sec-123"}""", r.body.readUtf8())
        assertTrue(r.getHeader("Content-Type")!!.startsWith("application/json"))
        assertFalse("Token=" in r.getHeader("Authorization")!!)
    }

    @Test fun authenticateByNameSendsCredentialsOnceInBody() {
        server.start(); server.enqueue(json(authBody))
        runBlocking { gateway.authenticateByName(address(), net(), "ben", "pa\"ss") }
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/Users/AuthenticateByName", r.path)
        assertEquals("""{"Username":"ben","Pw":"pa\"ss"}""", r.body.readUtf8())
        assertFalse(r.path!!.contains("pa"))
    }

    @Test fun wrongPasswordIsUnauthorisedWithoutLeakingIt() {
        server.start(); server.enqueue(json("""{"title":"Unauthorized"}""", 401))
        val e = kindOf { gateway.authenticateByName(address(), net(), "ben", "hunter2-secret") }
        assertEquals(AppError.Kind.UNAUTHORISED, e.kind)
        assertFalse(e.message!!.contains("hunter2"))
        assertFalse(generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("hunter2") == true })
    }

    @Test fun authenticationResultWithoutTokenIsAServerError() {
        server.start(); server.enqueue(json("""{"User":{"Id":"u","Name":"n"}}"""))
        assertEquals(AppError.Kind.SERVER_ERROR, kindOf { gateway.authenticateByName(address(), net(), "n", "p") }.kind)
    }

    @Test fun logoutPostsWithTokenHeader() {
        server.start(); server.enqueue(MockResponse().setResponseCode(204))
        runBlocking { gateway.logout(address(), signedInNet(address()) { "tok-XYZ" }) }
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/Sessions/Logout", r.path)
        assertTrue(r.getHeader("Authorization")!!.endsWith(", Token=\"tok-XYZ\""))
    }

    @Test fun unreachableServerIsNetworkError() {
        server.start(); val a = address(); val n = net(a); server.shutdown()
        assertEquals(AppError.Kind.NETWORK, kindOf { gateway.publicInfo(a, n) }.kind)
    }

    @Test fun cleartextIsBlockedUnlessAllowed() {
        server.start(); server.enqueue(json("{}"))
        assertEquals(AppError.Kind.CLEARTEXT_BLOCKED, kindOf { gateway.publicInfo(address(), net(cleartext = false)) }.kind)
        assertEquals(0, server.requestCount)
    }

    @Test fun compatibilityMapping() {
        fun c(v: String) = ServerInfo.compatibilityOf(v)
        assertEquals(ServerInfo.Compatibility.TOO_OLD, c("10.9.11"))
        assertEquals(ServerInfo.Compatibility.SUPPORTED, c("10.10.0"))
        assertEquals(ServerInfo.Compatibility.SUPPORTED, c("12.4.0"))
        assertEquals(ServerInfo.Compatibility.NEWER_UNTESTED, c("13.0.0"))
        assertEquals(ServerInfo.Compatibility.UNKNOWN, c("garbage"))
        assertEquals(ServerInfo.Compatibility.UNKNOWN, c(""))
    }

    @Test fun deviceNameIsSanitised() {
        assertEquals("Pixel 9 Pro", DeviceIdentity.sanitiseDeviceName("  Pixel\t9\u0000 Pro \""))
        assertEquals("Android", DeviceIdentity.sanitiseDeviceName(""))
        assertEquals(64, DeviceIdentity.sanitiseDeviceName("a".repeat(200)).length)
    }

    // ---- fix round 1

    @Test fun credentialRequestsAreNeverRedirected() {
        val other = MockWebServer().apply { start(); enqueue(json(authBody)) }
        try {
            server.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "http://127.0.0.1:${other.port}/Users/AuthenticateByName"))
            val e = kindOf { gateway.authenticateByName(address(), net(), "ben", "pw") }
            assertEquals(AppError.Kind.SERVER_NOT_FOUND, e.kind)
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test fun anyRedirectOnAnyEndpointIsAnError() {
        server.start(); server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, kindOf { gateway.publicInfo(address(), net()) }.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun hostileJsonIsAnAppErrorNotACrash() {
        server.start()
        val bodies = listOf("[]", "[1,2]", """{"AccessToken":5,"User":{"Id":"u"}}""", """{"AccessToken":"t","User":[]}""", "null", "\"str\"", "")
        bodies.forEach { server.enqueue(json(it)) }
        bodies.forEach { assertEquals(it, AppError.Kind.SERVER_ERROR, kindOf { gateway.authenticateByName(address(), net(), "n", "p") }.kind) }

        val qc = listOf("""{"Authenticated":"yes"}""", "[]", "42", """{"Authenticated":{}}""")
        qc.forEach { server.enqueue(json(it)) }
        qc.forEach { assertEquals(it, AppError.Kind.SERVER_ERROR, kindOf { gateway.quickConnectState(address(), net(), "s") }.kind) }

        server.enqueue(json("""{"Secret":7,"Code":[]}"""))
        assertEquals(AppError.Kind.SERVER_ERROR, kindOf { gateway.quickConnectInitiate(address(), net()) }.kind)
        server.enqueue(json("""{"Secret":"","Code":"123"}"""))
        assertEquals(AppError.Kind.SERVER_ERROR, kindOf { gateway.quickConnectInitiate(address(), net()) }.kind)
        server.enqueue(json("\"maybe\""))
        assertEquals(AppError.Kind.SERVER_ERROR, kindOf { gateway.quickConnectEnabled(address(), net()) }.kind)
    }

    @Test fun theTokenProviderIsReadLiveOnEveryRequest() {
        server.start(); server.enqueue(MockResponse().setResponseCode(204)); server.enqueue(MockResponse().setResponseCode(204))
        val token = AtomicReference<String?>("tok-XYZ")
        val a = address()
        runBlocking { gateway.logout(a, signedInNet(a) { token.get() }) }
        token.set(null)
        // Same configuration value: the cached client (and its live provider) is reused; with no token it refuses to send.
        assertEquals(AppError.Kind.NOT_SIGNED_IN, kindOf { gateway.logout(a, signedInNet(a) { "ignored" }) }.kind)
        assertTrue("Token=\"tok-XYZ\"" in server.takeRequest().getHeader("Authorization")!!)
        assertEquals(1, server.requestCount)
    }
}
