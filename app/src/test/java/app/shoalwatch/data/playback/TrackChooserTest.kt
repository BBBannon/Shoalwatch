package app.shoalwatch.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which tracks an item starts with: by identity (language, forced, hearing-impaired), in the documented order. */
class TrackChooserTest {
    private fun aud(index: Int, language: String?, default: Boolean = false) = AudioTrack(index, "a$index", language, "aac", 2, default)
    private fun sub(index: Int, language: String?, forced: Boolean = false, hi: Boolean = false, kind: SubtitleKind = SubtitleKind.TEXT) =
        SubtitleTrack(index, "s$index", language, "subrip", kind, forced, hi, isDefault = false, external = false, fileFormat = "srt")
    private val noCaptions = CaptionDefaults(false, null, 1f)
    private fun remembered(audio: String?, subtitle: String?, off: Boolean = false, forced: Boolean = false, hi: Boolean = false) =
        TrackChoice(audio, subtitle, subtitlesOff = off, forced = forced, hearingImpaired = hi)

    @Test fun rememberedLanguageWinsOverServerDefault() {
        val c = TrackChooser.choose(audio = listOf(aud(1, "jpn", default = true), aud(2, "eng")), subs = listOf(sub(3, "eng"), sub(4, "eng", forced = true)),
            remembered = TrackChoice("eng", "eng", subtitlesOff = false, forced = false, hearingImpaired = false),
            captions = CaptionDefaults(false, null, 1f), serverDefaultAudio = 1, serverDefaultSubtitle = null)
        assertEquals(TrackChooser.Choice(audioIndex = 2, subtitleIndex = 3), c)
    }

    @Test fun rememberedAudioPrefersThatLanguagesDefaultTrack() {
        val c = TrackChooser.choose(listOf(aud(1, "eng"), aud(2, "jpn"), aud(3, "jpn", default = true)), emptyList(), remembered("jpn", null), noCaptions, 1, null)
        assertEquals(3, c.audioIndex)
    }

    @Test fun rememberedLanguageMatchesTwoAndThreeLetterAndBibliographicCodes() {
        val c = TrackChooser.choose(listOf(aud(1, "eng", default = true), aud(2, "fre")), listOf(sub(3, "ger")), remembered("fr", "deu"), noCaptions, 1, null)
        assertEquals(TrackChooser.Choice(2, 3), c)
    }

    @Test fun anUnknownRememberedLanguageFallsBackToTheServerDefaultThenTheFirst() {
        assertEquals(2, TrackChooser.choose(listOf(aud(1, "eng"), aud(2, "jpn")), emptyList(), remembered("kor", null), noCaptions, 2, null).audioIndex)
        assertEquals(1, TrackChooser.choose(listOf(aud(1, "eng"), aud(2, "jpn")), emptyList(), null, noCaptions, null, null).audioIndex)
        assertEquals(1, TrackChooser.choose(listOf(aud(1, "eng"), aud(2, "jpn")), emptyList(), null, noCaptions, 99, null).audioIndex)
    }

    @Test fun rememberedFlagsPickTheMatchingTrackOfTheLanguage() {
        val subs = listOf(sub(3, "eng"), sub(4, "eng", forced = true), sub(5, "eng", hi = true))
        assertEquals(5, TrackChooser.choose(listOf(aud(1, "eng")), subs, remembered("eng", "eng", hi = true), noCaptions, 1, null).subtitleIndex)
        assertEquals(4, TrackChooser.choose(listOf(aud(1, "eng")), subs, remembered("eng", "eng", forced = true), noCaptions, 1, null).subtitleIndex)
    }

    @Test fun subtitlesOffStillShowsForcedInTheAudioLanguage() {
        val subs = listOf(sub(3, "eng"), sub(4, "eng", forced = true), sub(5, "jpn", forced = true))
        val c = TrackChooser.choose(listOf(aud(1, "eng", default = true)), subs, remembered("eng", null, off = true), noCaptions, 1, 3)
        assertEquals(TrackChooser.Choice(1, 4), c)
    }

    @Test fun subtitlesOffWithNoForcedTrackInTheAudioLanguageIsOffEvenIfTheServerDefaultsOne() {
        val subs = listOf(sub(3, "eng"), sub(5, "jpn", forced = true))
        val c = TrackChooser.choose(listOf(aud(1, "eng")), subs, remembered("eng", null, off = true), CaptionDefaults(true, "eng", 1f), 1, 3)
        assertNull(c.subtitleIndex)
    }

    @Test fun systemCaptionsOnPickTheCaptionLanguage() {
        val subs = listOf(sub(3, "eng"), sub(4, "fra"), sub(5, "fra", forced = true))
        val c = TrackChooser.choose(listOf(aud(1, "eng")), subs, null, CaptionDefaults(true, "fr-FR", 1f, phoneLanguage = "en-GB"), 1, 3)
        assertEquals(4, c.subtitleIndex)
    }

    @Test fun systemCaptionsWithoutThatLanguageUseThePhonesLanguageHearingImpairedFirst() {
        val subs = listOf(sub(3, "eng"), sub(4, "eng", hi = true), sub(5, "jpn"))
        val c = TrackChooser.choose(listOf(aud(1, "jpn")), subs, null, CaptionDefaults(true, "de", 1f, phoneLanguage = "en-GB"), 1, 5)
        assertEquals(4, c.subtitleIndex)
    }

    @Test fun withNoMemoryAForcedTrackInTheAudioLanguageComesBeforeTheServerDefault() {
        val subs = listOf(sub(3, "eng"), sub(4, "eng", forced = true))
        assertEquals(4, TrackChooser.choose(listOf(aud(1, "eng")), subs, null, noCaptions, 1, 3).subtitleIndex)
    }

    @Test fun nothingKnownFollowsTheServer() {
        val subs = listOf(sub(3, "eng"), sub(4, "jpn"))
        assertEquals(TrackChooser.Choice(1, 4), TrackChooser.choose(listOf(aud(1, "eng")), subs, null, noCaptions, 1, 4))
        assertEquals(TrackChooser.Choice(1, null), TrackChooser.choose(listOf(aud(1, "eng")), subs, null, noCaptions, 1, null))
    }

    @Test fun aServerDefaultThatIsNotATrackIsOff() {
        assertNull(TrackChooser.choose(listOf(aud(1, "eng")), listOf(sub(3, "eng")), null, noCaptions, 1, 9).subtitleIndex)
    }

    @Test fun noTracksAtAllIsNothing() {
        assertEquals(TrackChooser.Choice(null, null), TrackChooser.choose(emptyList(), emptyList(), remembered("eng", "eng"), CaptionDefaults(true, "en", 1f), null, null))
    }

    @Test fun languagesAreComparedByMeaning() {
        assertTrue(Languages.same("eng", "en-GB"))
        assertTrue(Languages.same("ger", "de"))
        assertFalse(Languages.same("eng", null))
        assertFalse(Languages.same("und", "und"))
        assertTrue("unknown on one side does not disagree", Languages.agree("eng", "und"))
        assertFalse(Languages.agree("eng", "fr"))
    }
}
