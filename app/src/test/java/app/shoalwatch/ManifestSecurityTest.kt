package app.shoalwatch

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element

/**
 * Guards the S2, S9 and S10 rows of the security table in docs/SPEC.md.
 *
 * The component and permission checks read the source manifest rather than the merged one, so
 * debug-only libraries (ui-test-manifest adds an exported activity) cannot make them flaky. What ships is
 * checked too: the `verifyReleaseManifest` Gradle task (part of `check` and `assembleRelease`) reads the MERGED
 * release manifest.
 */
@RunWith(RobolectricTestRunner::class)
class ManifestSecurityTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val toolsNs = "http://schemas.android.com/tools"

    private val manifest: Element by lazy {
        val file = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File).first { it.exists() }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        factory.newDocumentBuilder().parse(file).documentElement
    }

    private val application: Element
        get() = manifest.getElementsByTagName("application").item(0) as Element

    private fun Element.attr(name: String): String? =
        getAttributeNS(androidNs, name).takeIf { it.isNotEmpty() }

    @Test
    fun backupIsDisabled() {
        val info = ApplicationProvider.getApplicationContext<android.app.Application>().applicationInfo
        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals("false", application.attr("allowBackup"))
    }

    @Test
    fun dataExtractionRulesAreSet() {
        assertEquals("@xml/data_extraction_rules", application.attr("dataExtractionRules"))
    }

    @Test
    fun cleartextIsAllowedAtManifestLevelOnly() {
        // Required, not an oversight: users may opt in to plain-HTTP LAN servers per server.
        // The manifest cannot express per-user hosts, so CleartextGuard (an OkHttp interceptor,
        // net/CleartextGuard) is what actually refuses http:// for every server without that opt-in.
        val info = ApplicationProvider.getApplicationContext<android.app.Application>().applicationInfo
        assertTrue(info.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0)
        assertEquals("true", application.attr("usesCleartextTraffic"))
        assertEquals(null, application.attr("networkSecurityConfig"))
    }

    private fun elements(tag: String): List<Element> =
        manifest.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Element.toolsNode(): String? = getAttributeNS(toolsNs, "node").takeIf { it.isNotEmpty() }

    @Test
    fun theRequestedPermissionsAreTheMilestone4OnesAndTheAppLocksPrompt() {
        // A `tools:node="remove"` entry is a removal of a library's request, not a request. WAKE_LOCK, RECEIVE_BOOT_COMPLETED and
        // FOREGROUND_SERVICE arrive from work-runtime's own manifest (the merged release manifest is checked by verifyReleaseManifest).
        val permissions = elements("uses-permission").filter { it.toolsNode() != "remove" }.map { it.attr("name") }
        assertEquals(
            setOf(
                "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.POST_NOTIFICATIONS",
                "android.permission.USE_BIOMETRIC", // Milestone 7: the app lock's phone screen lock prompt
            ),
            permissions.toSet(),
        )
        assertEquals(permissions.size, permissions.toSet().size)
    }

    @Test
    fun wakeLockIsNoLongerRemovedBecauseWorkManagerHoldsOneWhileForegroundWorkRuns() {
        assertTrue(elements("uses-permission").none { it.attr("name") == "android.permission.WAKE_LOCK" })
    }

    @Test
    fun workManagersDiagnosticsReceiverAndDefaultInitialiserAreRemoved() {
        val diagnostics = elements("receiver").single { it.attr("name") == "androidx.work.impl.diagnostics.DiagnosticsReceiver" }
        assertEquals("remove", diagnostics.toolsNode())
        val initialiser = elements("meta-data").single { it.attr("name") == "androidx.work.WorkManagerInitializer" }
        assertEquals("remove", initialiser.toolsNode())
    }

    @Test
    fun theForegroundServiceIsMergedAsDataSync() {
        val service = elements("service").single { it.attr("name") == "androidx.work.impl.foreground.SystemForegroundService" }
        assertEquals("dataSync", service.attr("foregroundServiceType"))
        assertEquals("merge", service.toolsNode())
    }

    /**
     * D-1 (Milestone 5 device run): WorkManager's own RescheduleReceiver is switched on and off at run time, and Android saves
     * that switch lazily, so a reboot soon after a change can find it off with work still queued. This receiver is always on and
     * never exported; its delivery starts the process, and `ShoalwatchApp.onCreate` sends and requeues whatever waits.
     */
    @Test
    fun anAlwaysOnBootReceiverStartsTheAppAfterARestartAndIsNotExported() {
        val boot = elements("receiver").single { it.attr("name") == ".BootReceiver" }
        assertEquals("false", boot.attr("exported"))
        assertEquals(null, boot.attr("enabled")) // the default, true: never switched at run time
        val actions = boot.getElementsByTagName("action").let { list -> (0 until list.length).map { (list.item(it) as Element).attr("name") } }
        assertEquals(listOf("android.intent.action.BOOT_COMPLETED"), actions)
    }

    @Test
    fun onlyMainActivityAndTheCarActivityAreExported() {
        val tags = listOf("activity", "activity-alias", "service", "receiver", "provider")
        val exported = tags.flatMap { tag ->
            val nodes = application.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }.filter { component ->
            val explicit = component.attr("exported")
            val hasIntentFilter = component.getElementsByTagName("intent-filter").length > 0
            explicit == "true" || (explicit == null && hasIntentFilter)
        }.map { it.attr("name") }

        assertEquals(listOf(".ui.MainActivity", ".ui.car.ParkedCarActivity"), exported)
        assertNotNull(exported.firstOrNull())
    }

    private fun Element.children(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    /**
     * Milestone 9: Android Auto runs an app from outside Play only as a parked app (MAIN + CAR_LAUNCHER) in an allowed category
     * (game), and skips it when the package also has a Car App Library service (see "Android Auto (parked)" in docs/ARCHITECTURE.md).
     */
    @Test
    fun theCarActivityIsOnlyACarLauncherInItsOwnTask() {
        val car = elements("activity").single { it.attr("name") == ".ui.car.ParkedCarActivity" }
        assertEquals("true", car.attr("exported"))
        assertEquals("app.shoalwatch.car", car.attr("taskAffinity"))
        assertEquals("singleTask", car.attr("launchMode"))
        assertEquals("no other app may host it on its own display", null, car.attr("allowEmbedded"))
        val filter = car.children("intent-filter").single()
        assertEquals(listOf("android.intent.action.MAIN"), filter.children("action").map { it.attr("name") })
        assertEquals(listOf("android.intent.category.CAR_LAUNCHER"), filter.children("category").map { it.attr("name") })
        assertEquals(emptyList<Element>(), filter.children("data"))
        val launcher = elements("activity").single { it.attr("name") == ".ui.MainActivity" }
        assertTrue("the phone's activity is not on the car", launcher.children("category").none { it.attr("name") == "android.intent.category.CAR_LAUNCHER" })
    }

    @Test
    fun theAppIsAGameSoAndroidAutoAllowsTheParkedApp() {
        assertEquals("game", application.attr("appCategory"))
        val info = ApplicationProvider.getApplicationContext<android.app.Application>().applicationInfo
        assertEquals(ApplicationInfo.CATEGORY_GAME, info.category)
    }

    @Test
    fun noCarAppLibraryOrMediaServiceIsDeclared() {
        val actions = elements("action").map { it.attr("name") }
        assertTrue(actions.none { it == "androidx.car.app.CarAppService" || it == "android.media.browse.MediaBrowserService" || it!!.startsWith("androidx.media3.session.") })
        assertTrue(elements("category").none { it.attr("name")!!.startsWith("androidx.car.app.category.") })
        assertTrue(elements("meta-data").none { it.attr("name") == "com.google.android.gms.car.application" || it.attr("name") == "androidx.car.app.minCarApiLevel" })
    }

    /** Security review M8 F7: no shared user id, no test-only flag, and no permission asked through `uses-permission-sdk-23`. */
    @Test
    fun noSharedUserIdTestOnlyOrSdk23Permission() {
        assertEquals(null, manifest.attr("sharedUserId"))
        assertEquals(null, application.attr("testOnly"))
        assertEquals(emptyList<Element>(), elements("uses-permission-sdk-23"))
    }
}
