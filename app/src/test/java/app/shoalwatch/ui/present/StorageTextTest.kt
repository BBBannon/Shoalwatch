package app.shoalwatch.ui.present

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.R
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.downloads.VolumeUse
import app.shoalwatch.data.downloads.WatchedSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every Storage line in its exact words, each plural both ways, and digits 0-9 whatever the locale. */
@RunWith(RobolectricTestRunner::class)
class StorageTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val strings = Strings(context.resources)

    @Test fun volumes() {
        assertEquals("This phone: 12 GB free", StorageText.volume(strings, VolumeUse(StorageKind.INTERNAL, null, 12_000_000_000, 0, true)))
        assertEquals("Downloads use 640 MB", StorageText.volumeUsed(strings, VolumeUse(StorageKind.INTERNAL, null, 0, 640_000_000, true)))
        assertEquals("SD card: 1.8 GB free", StorageText.volume(strings, VolumeUse(StorageKind.REMOVABLE, "C", 1_800_000_000, 0, true)))
        val out = VolumeUse(StorageKind.REMOVABLE, "C", 0, 4_000_000_000, false)
        assertEquals("SD card: not in the phone. 4 GB of downloads are saved on it.", StorageText.volume(strings, out))
        assertNull(StorageText.volumeUsed(strings, out))
    }

    @Test fun rowsAndPlurals() {
        assertEquals("1 episode · 640 MB", StorageText.seriesRow(strings, 1, 640_000_000))
        assertEquals("3 episodes · 1.8 GB", StorageText.seriesRow(strings, 3, 1_800_000_000))
        assertEquals("1 watched", StorageText.watched(strings, 1))
        assertEquals("2 watched", StorageText.watched(strings, 2))
        assertEquals("Other profiles' downloads: 5 GB", StorageText.others(strings, 5_000_000_000))
        assertEquals("Delete downloads of The Show", StorageText.deleteSeries(strings, "The Show"))
        assertEquals("Delete download of The Film", StorageText.deleteFilm(strings, "The Film"))
        assertEquals("Downloaded by another profile", strings.get(R.string.storage_not_owned))
    }

    @Test fun confirms() {
        assertEquals("Delete the downloaded episodes of The Show?", StorageText.confirmSeriesTitle(strings, "The Show"))
        assertEquals("This frees 1.2 GB on this phone. Episodes downloaded by another profile stay.", StorageText.confirmSeriesBody(strings, 1_200_000_000))
        assertEquals("Delete this download? It takes up 4 GB on this phone.", StorageText.confirmFilm(strings, 4_000_000_000))
        assertEquals("Delete 1 watched download?", StorageText.watchedTitle(strings, WatchedSummary(1, 0, 0)))
        assertEquals("Delete 4 watched downloads?", StorageText.watchedTitle(strings, WatchedSummary(4, 0, 0)))
        val body = "This frees 3 GB on this phone. The shows still appear in Downloads and stay watched."
        assertEquals(body, StorageText.watchedBody(strings, WatchedSummary(4, 3_000_000_000, 0)))
        assertEquals("$body 1 of them is shared and is deleted for every profile.", StorageText.watchedBody(strings, WatchedSummary(4, 3_000_000_000, 1)))
        assertEquals("$body 2 of them are shared and are deleted for every profile.", StorageText.watchedBody(strings, WatchedSummary(4, 3_000_000_000, 2)))
    }

    @Test fun messagesAndLines() {
        assertEquals("Deleted 1 download.", StorageText.deleted(strings, 1))
        assertEquals("Deleted 3 downloads.", StorageText.deleted(strings, 3))
        assertEquals("Removed from your downloads. It stays on this phone for another profile.", StorageText.keptForOthers(strings))
        assertEquals("Next episodes weren't downloaded automatically: there isn't enough free space.", strings.get(R.string.storage_auto_next_blocked))
        assertEquals("Nothing is downloaded on this phone.", strings.get(R.string.storage_empty))
        assertEquals("Storage", strings.get(R.string.storage_title))
        assertEquals("Storage", strings.get(R.string.storage_action))
        assertEquals("Delete watched", strings.get(R.string.storage_delete_watched))
        assertEquals("Watched and deleted", strings.get(R.string.offline_released))
        assertEquals("Deleted from this phone. Download it again when you're online.", strings.get(R.string.offline_released_line))
    }

    @Test fun digitsAreNeverLocalised() {
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
        try {
            assertEquals("12 episodes · 1.8 GB", StorageText.seriesRow(strings, 12, 1_800_000_000))
            assertEquals("Deleted 12 downloads.", StorageText.deleted(strings, 12))
            assertEquals("Delete 12 watched downloads?", StorageText.watchedTitle(strings, WatchedSummary(12, 0, 0)))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}
