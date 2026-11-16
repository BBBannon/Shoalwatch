package app.shoalwatch.ui.present

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.data.downloads.DownloadFailure
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.QueueStatus
import app.shoalwatch.data.downloads.StorageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every download state and failure in its exact words; sizes in decimal units with plain digits. */
@RunWith(RobolectricTestRunner::class)
open class DownloadTextTest {
    private val strings = Strings(ApplicationProvider.getApplicationContext<Context>().resources)

    private fun info(state: DownloadState, progress: Float? = null, failure: DownloadFailure? = null, quality: DownloadQuality = DownloadQuality.ORIGINAL) =
        DownloadInfo("0".repeat(32), state, failure, quality, progress, null, true, StorageKind.INTERNAL, true)

    @Test fun everyStateHasItsLine() {
        assertEquals("Waiting", DownloadText.status(strings, info(DownloadState.QUEUED)))
        assertEquals("Downloading · 45%", DownloadText.status(strings, info(DownloadState.RUNNING, 0.45f)))
        assertEquals("Downloading, 45 percent", DownloadText.spokenStatus(strings, info(DownloadState.RUNNING, 0.45f)))
        assertEquals("Downloading", DownloadText.status(strings, info(DownloadState.RUNNING)))
        assertEquals("Checking it plays", DownloadText.status(strings, info(DownloadState.VERIFYING)))
        assertEquals("Paused", DownloadText.status(strings, info(DownloadState.PAUSED)))
        assertEquals("Ready to watch", DownloadText.status(strings, info(DownloadState.COMPLETE)))
    }

    @Test fun aConversionAddsItsQuality() {
        assertEquals("Ready to watch · High (1080p)", DownloadText.status(strings, info(DownloadState.COMPLETE, quality = DownloadQuality.HIGH)))
        assertEquals("Downloading · 10% · Medium (720p)", DownloadText.status(strings, info(DownloadState.RUNNING, 0.1f, quality = DownloadQuality.MEDIUM)))
        assertEquals("Waiting · Low (480p)", DownloadText.status(strings, info(DownloadState.QUEUED, quality = DownloadQuality.LOW)))
    }

    @Test fun everyFailureHasItsExactLine() {
        val lines = mapOf(
            DownloadFailure.NOT_ALLOWED to "Your server doesn't allow this account to download this.",
            DownloadFailure.NOT_PLAYABLE to "This phone can't play the original file. Download it in High, Medium or Low quality instead.",
            DownloadFailure.NO_SPACE to "There isn't enough free space. Delete some downloads or choose a smaller quality.",
            DownloadFailure.STORAGE_MISSING to "The SD card this was saving to isn't in the phone.",
            DownloadFailure.CHANGED_ON_SERVER to "The file kept changing on your server. Try again later.",
            DownloadFailure.SERVER_ERROR to "Your server had a problem sending this. Press Retry to try again.",
            DownloadFailure.SERVER_AUTH_REFUSED to "Your server wouldn't accept this app's sign-in for downloads. Check its setting for older apps, then press Retry.",
            DownloadFailure.SIGN_IN_NEEDED to "Sign in again to carry on with this download.",
            DownloadFailure.CHECK_FAILED to "This download didn't pass its check, so it was removed. Press Retry to download it again.",
            DownloadFailure.DAMAGED to "This download is damaged. Press Retry to download it again.",
            DownloadFailure.GONE_FROM_SERVER to "This is no longer on your server.",
            DownloadFailure.UNKNOWN to "Something went wrong with this download. Press Retry to try again.",
        )
        assertEquals(DownloadFailure.entries.toSet(), lines.keys)
        for ((f, line) in lines) assertEquals(line, DownloadText.status(strings, info(DownloadState.FAILED, failure = f, quality = DownloadQuality.HIGH)))
    }

    @Test fun theQueueLines() {
        assertEquals("Waiting for Wi-Fi", DownloadText.queueLine(strings, QueueStatus.WaitingForWifi))
        assertEquals("Waiting for a connection", DownloadText.queueLine(strings, QueueStatus.WaitingForConnection))
        assertEquals("Paused by Android after six hours. It carries on now that Shoalwatch is open.", DownloadText.queueLine(strings, QueueStatus.PausedByTimeLimit))
        assertNull(DownloadText.queueLine(strings, QueueStatus.Idle))
        assertNull(DownloadText.queueLine(strings, QueueStatus.Running(1, 2, 0.5f)))
    }

    @Test fun sizesAreDecimalWithPlainDigits() {
        assertEquals("1.8 GB", DownloadText.size(strings, 1_800_000_000))
        assertEquals("41 GB", DownloadText.size(strings, 41_000_000_000))
        assertEquals("9.8 GB", DownloadText.size(strings, 9_750_000_000))
        assertEquals("640 MB", DownloadText.size(strings, 640_000_000))
        assertEquals("2 GB", DownloadText.size(strings, 2_000_000_000))
        assertEquals("12 KB", DownloadText.size(strings, 12_000))
        assertEquals("12 episodes · 9.8 GB", DownloadText.seriesSummary(strings, 12, 9_800_000_000))
        assertEquals("1 episode · 640 MB", DownloadText.seriesSummary(strings, 1, 640_000_000))
    }
}

/** The same words with an Arabic locale: the digits never change. */
@Config(qualifiers = "ar")
class DownloadTextArabicDigitsTest : DownloadTextTest() {
    private val strings = Strings(ApplicationProvider.getApplicationContext<Context>().resources)

    @Test fun digitsStayLatin() {
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
        try {
            assertEquals("1.8 GB", DownloadText.size(strings, 1_800_000_000))
            assertEquals("Downloading · 45%", DownloadText.status(strings, DownloadInfo("0".repeat(32), DownloadState.RUNNING, null, DownloadQuality.ORIGINAL, 0.45f, null, true, StorageKind.INTERNAL, true)))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}
