package app.shoalwatch.data.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Device download settings in the private `download_prefs` file. */
@RunWith(RobolectricTestRunner::class)
class DownloadPrefsTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("download_prefs", Context.MODE_PRIVATE)

    @Test fun theDefaultsAreMobileDataOffInternalAndAskEachTime() {
        assertEquals(DownloadSettings(mobileData = false, storage = StorageKind.INTERNAL, volumeId = null, defaultQuality = null), DownloadPrefs(prefs).settings.value)
    }

    @Test fun changesEmitAndPersist() {
        val p = DownloadPrefs(prefs)
        p.setMobileData(true)
        assertEquals(true, p.settings.value.mobileData)
        p.setStorage(StorageKind.REMOVABLE, "ABCD-1234")
        p.setDefaultQuality(DownloadQuality.MEDIUM)
        val expected = DownloadSettings(true, StorageKind.REMOVABLE, "ABCD-1234", DownloadQuality.MEDIUM)
        assertEquals(expected, p.settings.value)
        assertEquals("read back by a new instance", expected, DownloadPrefs(prefs).settings.value)
        p.setDefaultQuality(null)
        p.setStorage(StorageKind.INTERNAL, "ignored")
        assertEquals(DownloadSettings(true, StorageKind.INTERNAL, null, null), DownloadPrefs(prefs).settings.value)
    }

    @Test fun anUnknownStoredValueReadsAsTheDefault() {
        prefs.edit().putString("storage", "CLOUD").putString("default_quality", "ULTRA").putString("volume_id", "../bad").commit()
        assertEquals(DownloadSettings(), DownloadPrefs(prefs).settings.value)
    }

    @Test fun aRemovableChoiceWithABadVolumeIdReadsAsInternal() {
        prefs.edit().putString("storage", "REMOVABLE").putString("volume_id", "bad/id").commit()
        assertEquals(DownloadSettings(), DownloadPrefs(prefs).settings.value)
        prefs.edit().putString("storage", "REMOVABLE").remove("volume_id").commit()
        assertEquals(DownloadSettings(), DownloadPrefs(prefs).settings.value)
    }

    @Test fun aWrongTypeStoredUnderAKeyReadsAsTheDefault() {
        prefs.edit().putInt("mobile_data", 1).putInt("storage", 3).commit()
        assertEquals(DownloadSettings(), DownloadPrefs(prefs).settings.value)
    }
}
