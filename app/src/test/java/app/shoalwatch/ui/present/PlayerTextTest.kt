package app.shoalwatch.ui.present

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.R
import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.Quality
import app.shoalwatch.data.playback.SegmentKind
import app.shoalwatch.data.playback.SleepChoice
import app.shoalwatch.data.playback.TranscodeReason
import app.shoalwatch.data.playback.VideoRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The player's words: how the video is being played, HDR, times, speeds, delays, skips. */
@RunWith(RobolectricTestRunner::class)
class PlayerTextTest {
    private val strings = Strings(ApplicationProvider.getApplicationContext<Context>().resources)
    private fun converting(reason: TranscodeReason, fellBack: Boolean = false) =
        PlayerText.streamInfo(strings, PlayMethod.TRANSCODE, setOf(reason), fellBack)

    @Test fun directPlayAndDirectStreamSayWhatIsHappening() {
        assertEquals("Playing the original file.", PlayerText.streamInfo(strings, PlayMethod.DIRECT_PLAY, emptySet(), false))
        assertEquals(
            "Playing the original video. Your server is repackaging it for this phone.",
            PlayerText.streamInfo(strings, PlayMethod.DIRECT_STREAM, setOf(TranscodeReason.CONTAINER), false),
        )
        assertNull(PlayerText.streamInfo(strings, null, emptySet(), false))
    }

    @Test fun everyTranscodeReasonHasItsSentence() {
        val lead = "Your server is converting this video for this phone"
        assertEquals("$lead because this phone can't play its video format.", converting(TranscodeReason.VIDEO_CODEC))
        assertEquals("$lead because this phone can't play its sound format.", converting(TranscodeReason.AUDIO_CODEC))
        assertEquals("$lead because this phone can't open its file type.", converting(TranscodeReason.CONTAINER))
        assertEquals("$lead to fit the quality you chose.", converting(TranscodeReason.BITRATE))
        assertEquals("$lead to fit the quality you chose.", converting(TranscodeReason.RESOLUTION))
        assertEquals("$lead so its HDR colours look right on this screen.", converting(TranscodeReason.VIDEO_RANGE))
        assertEquals("$lead so it can draw the subtitles into the picture.", converting(TranscodeReason.SUBTITLE))
        assertEquals("$lead to mix the sound down for this phone.", converting(TranscodeReason.AUDIO_CHANNELS))
        assertEquals("$lead because this phone couldn't play the original.", converting(TranscodeReason.DIRECT_PLAY_ERROR))
        assertEquals("$lead.", converting(TranscodeReason.OTHER))
        assertEquals("$lead.", PlayerText.streamInfo(strings, PlayMethod.TRANSCODE, emptySet(), false))
    }

    @Test fun aFallBackSaysThePhoneCouldNotPlayTheOriginalWhateverTheServerSaid() {
        assertEquals(
            "Your server is converting this video for this phone because this phone couldn't play the original.",
            converting(TranscodeReason.BITRATE, fellBack = true),
        )
    }

    @Test fun theFirstReasonIsTheOneSaid() {
        val reasons = linkedSetOf(TranscodeReason.AUDIO_CODEC, TranscodeReason.VIDEO_CODEC)
        assertEquals(
            "Your server is converting this video for this phone because this phone can't play its sound format.",
            PlayerText.streamInfo(strings, PlayMethod.TRANSCODE, reasons, false),
        )
    }

    @Test fun hdrOnAnHdrScreenAndOnAnSdrScreen() {
        assertEquals("HDR video, shown in HDR.", PlayerText.hdrLine(strings, VideoRange.HDR10, PlayMethod.DIRECT_PLAY, emptySet(), dolbyVisionScreen = false))
        assertEquals("HDR video, shown in HDR.", PlayerText.hdrLine(strings, VideoRange.HLG, PlayMethod.DIRECT_PLAY, emptySet(), dolbyVisionScreen = false))
        assertEquals(
            "HDR video. This screen can't show HDR, so your server is converting the colours.",
            PlayerText.hdrLine(strings, VideoRange.HDR10, PlayMethod.TRANSCODE, setOf(TranscodeReason.VIDEO_RANGE), dolbyVisionScreen = false),
        )
        assertEquals("Dolby Vision video, shown as HDR10.", PlayerText.hdrLine(strings, VideoRange.DOVI_WITH_HDR10, PlayMethod.DIRECT_PLAY, emptySet(), dolbyVisionScreen = false))
        assertEquals("HDR video, shown in HDR.", PlayerText.hdrLine(strings, VideoRange.DOVI_WITH_HDR10, PlayMethod.DIRECT_PLAY, emptySet(), dolbyVisionScreen = true))
        assertNull(PlayerText.hdrLine(strings, VideoRange.SDR, PlayMethod.DIRECT_PLAY, emptySet(), false))
        assertNull(PlayerText.hdrLine(strings, VideoRange.DOVI_WITH_SDR, PlayMethod.DIRECT_PLAY, emptySet(), false))
        assertNull(PlayerText.hdrLine(strings, null, null, emptySet(), false))
    }

    @Test fun timesUseDigitsAndHoursPastAnHour() {
        assertEquals("0:00", PlayerText.clock(0))
        assertEquals("0:09", PlayerText.clock(9_999))
        assertEquals("12:30", PlayerText.clock(750_000))
        assertEquals("45:00", PlayerText.clock(2_700_000))
        assertEquals("1:02:03", PlayerText.clock(3_723_000))
        assertEquals("0:00", PlayerText.clock(-5))
        assertEquals("12:30 of 45:00", PlayerText.seekState(strings, 750_000, 2_700_000))
    }

    @Test @Config(qualifiers = "ar") fun timesKeepWesternDigitsInAnotherLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("1:02:03", PlayerText.clock(3_723_000))
            assertEquals("1.25", PlayerText.speedNumber(1.25f))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun speedsDelaysAndChoicesHaveWords() {
        assertEquals("1×", PlayerText.speed(strings, 1f))
        assertEquals("0.75×", PlayerText.speed(strings, 0.75f))
        assertEquals("1.5×", PlayerText.speed(strings, 1.5f))
        assertEquals("0 seconds", PlayerText.delay(strings, 0))
        assertEquals("+0.5 seconds", PlayerText.delay(strings, 500))
        assertEquals("−1.2 seconds", PlayerText.delay(strings, -1_200))
        assertEquals(strings.get(R.string.quality_maximum), PlayerText.quality(strings, Quality.MAXIMUM))
        assertEquals("1.5 Mbps (480p)", PlayerText.quality(strings, Quality.MBPS_1_5))
        assertEquals("30 minutes", PlayerText.sleep(strings, SleepChoice.MIN_30))
        assertEquals("End of this episode", PlayerText.sleep(strings, SleepChoice.END_OF_ITEM))
        assertEquals("Off", PlayerText.sleep(strings, SleepChoice.OFF))
    }

    @Test fun eachSegmentHasItsSkipLabel() {
        val labels = SegmentKind.entries.associateWith { strings.get(PlayerText.skipLabel(it)) }
        assertEquals(
            mapOf(
                SegmentKind.INTRO to "Skip intro", SegmentKind.OUTRO to "Skip credits", SegmentKind.RECAP to "Skip recap",
                SegmentKind.PREVIEW to "Skip preview", SegmentKind.COMMERCIAL to "Skip advert",
            ),
            labels,
        )
    }
}
