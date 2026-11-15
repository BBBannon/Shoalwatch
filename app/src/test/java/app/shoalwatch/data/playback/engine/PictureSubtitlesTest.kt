package app.shoalwatch.data.playback.engine

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Subtitle-parsers review I-1: text tracks inside a directly played file are never parsed by Media3's text parsers during
 * extraction. The engine's media source factory (and through it the extractors) only has a parser for the picture formats
 * the app draws from the stream; a text track's samples pass through unparsed (and its renderer is never enabled).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class PictureSubtitlesTest {
    private fun format(mime: String) = Format.Builder().setSampleMimeType(mime).build()

    private val text = listOf(
        MimeTypes.TEXT_SSA, MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_MP4VTT, MimeTypes.APPLICATION_TTML,
        MimeTypes.APPLICATION_TX3G, MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708, MimeTypes.APPLICATION_MP4CEA608,
        MimeTypes.TEXT_UNKNOWN, MimeTypes.APPLICATION_MEDIA3_CUES,
    )
    private val picture = listOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS)

    @Test fun everyTextFormatIsRefusedAndNoParserCanBeMadeForIt() {
        for (mime in text) {
            assertFalse(mime, PictureSubtitles.supportsFormat(format(mime)))
            assertTrue(mime, runCatching { PictureSubtitles.create(format(mime)) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun thePictureFormatsTheAppDrawsFromTheStreamAreParsed() {
        // As the extractors give them: VobSub with its idx header, DVB with its composition and ancillary page ids.
        val init = mapOf(MimeTypes.APPLICATION_VOBSUB to "size: 720x480\npalette: 000000, ffffff\n".toByteArray(), MimeTypes.APPLICATION_DVBSUBS to byteArrayOf(0, 1, 0, 1))
        for (mime in picture) {
            val f = Format.Builder().setSampleMimeType(mime).setInitializationData(listOfNotNull(init[mime])).build()
            assertTrue(mime, PictureSubtitles.supportsFormat(f))
            PictureSubtitles.create(f)
            PictureSubtitles.getCueReplacementBehavior(f)
        }
    }

    private fun field(of: Any, name: String): Any? = generateSequence<Class<*>>(of.javaClass) { it.superclass }
        .mapNotNull { c -> runCatching { c.getDeclaredField(name) }.getOrNull() }.first().apply { isAccessible = true }.get(of)

    /** The real engine: its media source factory and the extractors factory under it both hold [PictureSubtitles]. */
    @Test fun theEngineGivesItsMediaSourcesAndExtractorsOnlyThePictureParsers() {
        val engine = ExoPlayerEngine(ApplicationProvider.getApplicationContext())
        try {
            val sources = engine.mediaSources
            assertSame(PictureSubtitles, field(sources, "subtitleParserFactory"))
            val extractors = field(field(sources, "delegateFactoryLoader")!!, "extractorsFactory")!!
            assertSame("the extractors' own default parser factory is replaced", PictureSubtitles, field(extractors, "subtitleParserFactory"))
        } finally {
            engine.release()
        }
    }
}
