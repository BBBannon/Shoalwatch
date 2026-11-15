package app.shoalwatch.data.playback.engine

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.dvb.DvbParser
import androidx.media3.extractor.text.pgs.PgsParser
import androidx.media3.extractor.text.vobsub.VobsubParser

/**
 * The only subtitle parsers the player has (subtitle-parsers review I-1): PGS, VobSub and DVB, the picture formats the app
 * draws from the stream. Media3's default factory would also parse every TEXT track inside a directly played MKV/MP4/WebM
 * during extraction (SSA, SubRip through Android's HTML parser, WebVTT, tx3g, TTML), on the loading thread and before
 * track selection:
 * the parsers four reviews found quadratic on crafted input. With this factory a text format is unsupported, so Media3 1.9.2's
 * `SubtitleTranscodingTrackOutput` creates no parser for it and passes its samples through unparsed under the original type;
 * the text renderer is never enabled for such a track (`ExoPlayerEngine.applyTracks`), and if it were, Media3's legacy
 * decoding is off, so it would fail the renderer rather than parse. Text subtitles are fetched as files and read by the app.
 *
 * This file is the one place `Media3BoundaryTest` lets name Media3's subtitle parser interface.
 */
@OptIn(UnstableApi::class)
internal object PictureSubtitles : SubtitleParser.Factory {
    private val PICTURE = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS)

    override fun supportsFormat(format: Format): Boolean = format.sampleMimeType in PICTURE

    override fun getCueReplacementBehavior(format: Format): Int = when (format.sampleMimeType) {
        MimeTypes.APPLICATION_PGS -> PgsParser.CUE_REPLACEMENT_BEHAVIOR
        MimeTypes.APPLICATION_VOBSUB -> VobsubParser.CUE_REPLACEMENT_BEHAVIOR
        MimeTypes.APPLICATION_DVBSUBS -> DvbParser.CUE_REPLACEMENT_BEHAVIOR
        else -> throw IllegalArgumentException("Unsupported subtitle type.")
    }

    override fun create(format: Format): SubtitleParser = when (format.sampleMimeType) {
        MimeTypes.APPLICATION_PGS -> PgsParser()
        MimeTypes.APPLICATION_VOBSUB -> VobsubParser(format.initializationData)
        MimeTypes.APPLICATION_DVBSUBS -> DvbParser(format.initializationData)
        else -> throw IllegalArgumentException("Unsupported subtitle type.")
    }
}
