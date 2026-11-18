package app.shoalwatch.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.shoalwatch.R
import app.shoalwatch.ShoalwatchApp
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.Session
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.LockAfter
import app.shoalwatch.security.LockState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Milestone 7 on a device (Task 8): a protected profile is reachable only by its own PIN, on every path this test can drive.
 *
 * One server and three accounts (ben, anna, kid), seeded through the real `AppGraph` (plain http to an in-process
 * `MockWebServer`, as `SyncSmokeTest`), ben protected with PIN 2468 through `lockParts.profileLocks`, kid active, "Lock after"
 * Straight away. The clean-up also runs when seeding fails part way, and puts back the profile that was active and "Lock after".
 * The controller runs it (never run by an implementer: no emulator or device).
 */
@RunWith(AndroidJUnit4::class)
class LockSmokeTest {
    private val serverId = "4c0c4000000000000000000000000c01"
    private val ben = "be000000000000000000000000000b01"
    private val anna = "a0000000000000000000000000000a02"
    private val kid = "c1d00000000000000000000000000c03"
    private val tokens = mapOf(ben to "tok-ben-1111", anna to "tok-anna-2222", kid to "tok-kid-3333")
    private val names = mapOf(ben to "ben", anna to "anna", kid to "kid")
    private val benPassword = "ben-server-password"
    private val serverName = "Lock Den"
    private val pin = "2468"

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = ShoalwatchApp.graph(context)
    private val parts get() = graph.lockParts
    private val mock = MockWebServer()

    private var previous: Session? = null
    private var previousLockAfter: LockAfter? = null
    private var captured = false

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
    private fun empty() = json("""{"Items":[],"TotalRecordCount":0,"StartIndex":0}""")

    private fun userOf(r: RecordedRequest): String? {
        val auth = r.getHeader("Authorization").orEmpty()
        return tokens.entries.firstOrNull { it.value in auth }?.key
    }

    private fun answer(r: RecordedRequest): MockResponse {
        val path = r.path.orEmpty().substringBefore('?')
        if (path == "/System/Info/Public") return json("""{"Id":"$serverId","ServerName":"$serverName","Version":"10.10.3"}""")
        if (path == "/Users/AuthenticateByName" && r.method == "POST") {
            val body = r.body.clone().readUtf8()
            return if ("\"ben\"" in body && benPassword in body) {
                json("""{"User":{"Id":"$ben","Name":"ben"},"AccessToken":"${tokens[ben]}","ServerId":"$serverId"}""")
            } else {
                MockResponse().setResponseCode(401)
            }
        }
        val user = userOf(r) ?: return MockResponse().setResponseCode(401)
        return when (path) {
            "/Users/Me" -> json("""{"Id":"$user","Name":"${names[user]}","Policy":{"IsAdministrator":false,"IsDisabled":false}}""")
            "/UserViews", "/UserItems/Resume", "/Shows/NextUp", "/Items" -> empty()
            "/Items/Latest" -> json("[]")
            else -> if (path.startsWith("/Sessions/")) MockResponse().setResponseCode(204) else MockResponse().setResponseCode(404)
        }
    }

    private val seed = object : ExternalResource() {
        override fun before() {
            try {
                seedEverything()
            } catch (t: Throwable) {
                cleanUp() // `after()` is not called when `before()` throws
                throw t
            }
        }

        private fun seedEverything() {
            mock.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
            }
            mock.start()
            runBlocking {
                graph.tokensWarmed.join()
                // Seeding protects ben as the first PIN on the phone; with another profile already protected that is refused.
                assumeTrue("no other protected profile on this device", parts.profileLocks.protectedProfiles.first().isEmpty())
                previous = graph.serverCatalog.activeSession().first()
                previousLockAfter = parts.lockPrefs.settings.value.lockAfter
                captured = true
                graph.serverStore.upsertServer(
                    Server(
                        id = serverId, name = serverName, version = "10.10.3", homeAddress = ServerAddress.parse("http://127.0.0.1:${mock.port}"),
                        awayAddress = null, allowCleartext = true, pinnedSpkiSha256 = null, clientCertAlias = null, customHeaders = emptyMap(),
                    ),
                )
                graph.serverStore.upsertAccount(Account(serverId, ben, "ben", tokens.getValue(ben)), makeActive = false)
                graph.serverStore.upsertAccount(Account(serverId, anna, "anna", tokens.getValue(anna)), makeActive = false)
                graph.serverStore.upsertAccount(Account(serverId, kid, "kid", tokens.getValue(kid)), makeActive = true)
                // Before ben is protected: a lock setting needs the household check, which passes while nobody is protected.
                parts.lockPrefs.setLockAfter(LockAfter.IMMEDIATELY)
                assertTrue("ben protected (the first PIN on this phone)", parts.profileLocks.protect(Session(serverId, ben), pin.toCharArray()))
            }
        }

        override fun after() = cleanUp()

        /** Each step on its own, so one failing never skips the rest. Safe if nothing was seeded or the mock never started. */
        private fun cleanUp() {
            runCatching { runBlocking { graph.serverStore.removeServer(serverId) } } // the accounts, ben's lock and their settings
            runCatching { previousLockAfter?.let { runBlocking { parts.lockPrefs.setLockAfter(it) } } } // after ben's lock has gone
            runCatching {
                val back = previous
                if (captured && back != null && back.serverId != serverId) runBlocking { graph.serverStore.setActive(back.serverId, back.userId) }
            }
            runCatching { mock.shutdown() }
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)
    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun described(label: String) = compose.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()
    private fun active(): Session? = runBlocking { graph.serverCatalog.activeSession().first() }

    private fun tap(text: String) {
        val node = compose.onAllNodes(hasText(text).and(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    /**
     * "Add another account" in the "Lock Den" card only (review I2 of Tasks 7-8): another saved server listed first must never be
     * the one signed in to. The card is the node whose own children include the server's name.
     */
    private fun tapAddAccountOnThisServer() {
        val node = compose.onNode(
            hasText(string(R.string.server_add_account)).and(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
                .and(hasAnyAncestor(hasAnyChild(hasText(serverName)))),
        )
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun tapDescribed(label: String) = compose.onAllNodes(hasContentDescription(label)).onFirst().performClick()

    /** Types [digits] into the PIN field of the dialog on top, replacing what was there. */
    private fun typeInDialog(digits: String) {
        val field = compose.onAllNodes(hasSetTextAction().and(hasAnyAncestor(isDialog()))).onFirst()
        field.performTextClearance()
        field.performTextInput(digits)
    }

    private fun homeAs(user: String) {
        waitFor { active() == Session(serverId, user) && parts.lockGate.state.value == LockState.Open && described(string(R.string.account_menu_cd)) }
    }

    private fun openAccountMenu() {
        tapDescribed(string(R.string.account_menu_cd))
        compose.waitForIdle()
    }

    @Test fun aProtectedProfileIsReachedOnlyByItsPin() {
        homeAs(kid)

        // Switch: from Home's account menu, ben asks for his PIN.
        openAccountMenu()
        tapDescribed(string(R.string.profile_protected_cd, "ben"))
        waitFor { shown(string(R.string.switch_pin_title, "ben")) }
        typeInDialog("1111")
        tap(string(R.string.switch_pin_confirm))
        val wrong = compose.activity.resources.getQuantityString(R.plurals.pin_wrong, 4, 4)
        waitFor { shown(wrong) }
        assertEquals("a wrong PIN switches nothing", Session(serverId, kid), active())
        typeInDialog(pin)
        tap(string(R.string.switch_pin_confirm))
        homeAs(ben)
        openAccountMenu()
        assertTrue("the top bar's account is ben", shown(string(R.string.account_current, "ben")))
        tap(string(R.string.account_current, "ben")) // closes the menu; the current profile switches nothing
        compose.waitForIdle()

        // Leaving: Straight away, so coming back asks for ben's PIN and shows nothing else.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { shown(string(R.string.lock_profile_title, "ben")) }
        assertFalse("nothing of Home under the lock", described(string(R.string.account_menu_cd)))
        assertFalse(shown(string(R.string.account_current, "ben")))
        tap(string(R.string.lock_other_row, "kid", serverName))
        homeAs(kid) // kid is not protected: no PIN

        // Sign-in path: signing ben in again with his server password shows ben's lock straight after, not Home.
        openAccountMenu()
        tap(string(R.string.home_manage))
        waitFor { shown(string(R.string.server_add_account)) }
        tapAddAccountOnThisServer()
        waitFor { shown(string(R.string.qc_use_password)) || compose.onAllNodes(hasText(string(R.string.username_label))).fetchSemanticsNodes().isNotEmpty() }
        if (shown(string(R.string.qc_use_password))) tap(string(R.string.qc_use_password))
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2 }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("ben")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput(benPassword)
        tap(string(R.string.signin_action))
        waitFor { shown(string(R.string.lock_profile_title, "ben")) }
        assertEquals(Session(serverId, ben), active())
        assertFalse("never Home as ben without the PIN", described(string(R.string.account_menu_cd)))
        tap(string(R.string.lock_other_row, "kid", serverName))
        homeAs(kid)

        // Deep link: Navigation's implicit route link is stripped; the app stays at Home. Started from the activity itself, on top,
        // so this same instance receives it (`onNewIntent`) and the compose rule still sees its screen.
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("android-app://androidx.navigation/download_settings"))
            .setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        compose.activityRule.scenario.onActivity { it.startActivity(link) }
        compose.waitForIdle()
        homeAs(kid)
        assertFalse("not Download settings", shown(string(R.string.dl_settings_title)))
        compose.activityRule.scenario.onActivity { assertNull("the activity's intent has no data", it.intent.data) }

        // Household: kid turning on sharing needs a grown-up's PIN.
        tap(string(R.string.nav_downloads))
        waitFor { shown(string(R.string.dl_settings_action)) || described(string(R.string.dl_settings_action)) }
        if (shown(string(R.string.dl_settings_action))) tap(string(R.string.dl_settings_action)) else tapDescribed(string(R.string.dl_settings_action))
        waitFor { shown(string(R.string.dl_share)) }
        tap(string(R.string.dl_share))
        waitFor { shown(string(R.string.grownup_title)) }
        tap(string(R.string.action_cancel))
        compose.waitForIdle()
        assertFalse("Cancel changes nothing", runBlocking { graph.downloads.sharing(serverId).first() })
        tap(string(R.string.dl_share))
        waitFor { shown(string(R.string.grownup_title)) }
        typeInDialog(pin)
        tap(string(R.string.grownup_confirm))
        waitFor { runBlocking { graph.downloads.sharing(serverId).first() } }

        // Recents: API 33+ has no way to read the screenshot switch back; on 29–32 the window is FLAG_SECURE while ben is protected.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.i("LockSmokeTest", "recents check recorded only: SDK_INT=${Build.VERSION.SDK_INT}")
        } else {
            compose.activityRule.scenario.onActivity {
                assertTrue("FLAG_SECURE while a profile is protected", it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
    }
}
