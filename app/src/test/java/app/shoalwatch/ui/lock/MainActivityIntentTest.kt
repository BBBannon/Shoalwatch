package app.shoalwatch.ui.lock

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import app.shoalwatch.data.downloads.DownloadNotifications
import app.shoalwatch.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Links into the app are ignored: only the download notice's `open_tab=downloads` survives (Milestone 6 check M9). */
@RunWith(RobolectricTestRunner::class)
class MainActivityIntentTest {
    private val deepLink = Uri.parse("android-app://androidx.navigation/download_settings")

    private fun Intent.extraKeys(): Set<String> = extras?.keySet().orEmpty()

    private fun assertPlainLauncher(i: Intent) {
        assertEquals(Intent.ACTION_MAIN, i.action)
        assertEquals(setOf(Intent.CATEGORY_LAUNCHER), i.categories)
        assertEquals(MainActivity::class.java.name, i.component?.className)
        assertNull(i.data)
        assertNull(i.clipData)
        assertNull(i.selector)
    }

    @Test fun aNavigationDeepLinkLosesItsData() {
        val clean = MainActivity.sanitised(Intent(Intent.ACTION_VIEW, deepLink))
        assertPlainLauncher(clean)
        assertEquals(emptySet<String>(), clean.extraKeys())
    }

    @Test fun theDownloadNoticesTabIsKept() {
        val clean = MainActivity.sanitised(Intent().putExtra(DownloadNotifications.EXTRA_OPEN_TAB, DownloadNotifications.TAB_DOWNLOADS))
        assertPlainLauncher(clean)
        assertEquals(setOf(DownloadNotifications.EXTRA_OPEN_TAB), clean.extraKeys())
        assertEquals(DownloadNotifications.TAB_DOWNLOADS, MainActivity.requestedTab(clean))
    }

    @Test fun anyOtherTabIsDropped() {
        val clean = MainActivity.sanitised(Intent().putExtra(DownloadNotifications.EXTRA_OPEN_TAB, "servers"))
        assertEquals(emptySet<String>(), clean.extraKeys())
        assertNull(MainActivity.requestedTab(clean))
    }

    @Test fun clipDataASelectorAndOtherExtrasAreDropped() {
        val dirty = Intent(Intent.ACTION_VIEW, deepLink)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(1))
            .putExtra("x", "y")
            .putExtra(DownloadNotifications.EXTRA_OPEN_TAB, DownloadNotifications.TAB_DOWNLOADS)
        dirty.clipData = ClipData.newRawUri("", deepLink)
        dirty.selector = Intent(Intent.ACTION_VIEW, deepLink)
        val clean = MainActivity.sanitised(dirty)
        assertPlainLauncher(clean)
        assertEquals(setOf(DownloadNotifications.EXTRA_OPEN_TAB), clean.extraKeys())
    }

    @Test fun nothingIsAPlainLauncherIntent() {
        val clean = MainActivity.sanitised(null)
        assertPlainLauncher(clean)
        assertEquals(emptySet<String>(), clean.extraKeys())
    }
}
