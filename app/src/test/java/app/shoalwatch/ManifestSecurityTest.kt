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
 * debug-only libraries (ui-test-manifest adds an exported activity) cannot make them flaky.
 */
@RunWith(RobolectricTestRunner::class)
class ManifestSecurityTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

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
        // a later task) is what actually refuses http:// for every server without that opt-in.
        val info = ApplicationProvider.getApplicationContext<android.app.Application>().applicationInfo
        assertTrue(info.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0)
        assertEquals("true", application.attr("usesCleartextTraffic"))
        assertEquals(null, application.attr("networkSecurityConfig"))
    }

    @Test
    fun onlyInternetAndNetworkStatePermissionsAreRequested() {
        val permissions = manifest.getElementsByTagName("uses-permission").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as Element).attr("name") }
        }
        assertEquals(
            setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"),
            permissions.toSet(),
        )
        assertEquals(permissions.size, permissions.toSet().size)
    }

    @Test
    fun onlyMainActivityIsExported() {
        val tags = listOf("activity", "activity-alias", "service", "receiver", "provider")
        val exported = tags.flatMap { tag ->
            val nodes = application.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }.filter { component ->
            val explicit = component.attr("exported")
            val hasIntentFilter = component.getElementsByTagName("intent-filter").length > 0
            explicit == "true" || (explicit == null && hasIntentFilter)
        }.map { it.attr("name") }

        assertEquals(listOf(".ui.MainActivity"), exported)
        assertNotNull(exported.firstOrNull())
    }
}
