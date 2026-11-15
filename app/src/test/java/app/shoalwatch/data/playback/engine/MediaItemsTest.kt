package app.shoalwatch.data.playback.engine

import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.playback.PlaybackFakes
import app.shoalwatch.data.playback.StreamSource
import app.shoalwatch.net.BlockedRequestException
import app.shoalwatch.ui.browse.BEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.ExecutionException

/** What Media3 is handed (the verified address + path + query, no token, the HLS type) and how its errors are read (by code only). */
@RunWith(RobolectricTestRunner::class)
class MediaItemsTest {
    private val route = PlaybackFakes.route(BEN)

    private fun item(source: StreamSource) = EngineItem(route, source, PlaybackFakes.ITEM)

    @Test fun aDirectStreamIsTheVerifiedAddressPlusPathAndQueryWithNoMimeType() {
        val source = MediaPaths.directStream(PlaybackFakes.ITEM, PlaybackFakes.SOURCE, "abc123")
        val media = MediaItems.build(item(source), "Title", null)
        val uri = media.localConfiguration!!.uri
        assertEquals("https", uri.scheme); assertEquals("media.example:8920", uri.encodedAuthority)
        assertEquals("/Videos/${PlaybackFakes.ITEM}/stream", uri.path)
        assertEquals("true", uri.getQueryParameter("static")); assertEquals(PlaybackFakes.SOURCE, uri.getQueryParameter("mediaSourceId"))
        assertNull(media.localConfiguration!!.mimeType)
        assertTrue(media.localConfiguration!!.subtitleConfigurations.isEmpty())
        assertEquals(PlaybackFakes.ITEM, media.mediaId)
        assertEquals("Title", media.mediaMetadata.title)
    }

    @Test fun hlsSetsTheM3u8MimeTypeAndCarriesNoCredential() {
        val source = StreamSource("/videos/${PlaybackFakes.ITEM}/master.m3u8", listOf("PlaySessionId" to "abc", "MediaSourceId" to PlaybackFakes.SOURCE), hls = true)
        val media = MediaItems.build(item(source), null, null)
        assertEquals(MimeTypes.APPLICATION_M3U8, media.localConfiguration!!.mimeType)
        val text = media.localConfiguration!!.uri.toString()
        assertTrue(text.startsWith("https://media.example:8920/videos/${PlaybackFakes.ITEM}/master.m3u8?"))
        for (name in listOf("api_key", "ApiKey", "X-Emby-Token", "token")) assertFalse(name, text.contains(name, ignoreCase = true))
    }

    @Test fun anEngineItemNeverPrintsItsUrl() {
        val printed = item(MediaPaths.directStream(PlaybackFakes.ITEM, PlaybackFakes.SOURCE, null)).toString()
        assertEquals("EngineItem(<redacted>)", printed)
        assertFalse(printed.contains("media.example"))
    }

    private fun error(code: Int, cause: Throwable? = null) = PlaybackException("not read", cause, code)

    @Test fun decoderAndFormatErrorsMapByCode() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )) assertEquals(EngineFailure.DECODER, MediaItems.failureOf(error(code)))
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        )) assertEquals(EngineFailure.FORMAT_UNSUPPORTED, MediaItems.failureOf(error(code)))
    }

    @Test fun networkLiveWindowAndOtherErrorsMapByCode() {
        assertEquals(EngineFailure.NETWORK, MediaItems.failureOf(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertEquals(EngineFailure.NETWORK, MediaItems.failureOf(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
        assertEquals(EngineFailure.BEHIND_LIVE_WINDOW, MediaItems.failureOf(error(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)))
        assertEquals(EngineFailure.OTHER, MediaItems.failureOf(error(PlaybackException.ERROR_CODE_UNSPECIFIED)))
    }

    private fun badStatus(status: Int) = HttpDataSource.InvalidResponseCodeException(
        status, null, null, emptyMap(), DataSpec(Uri.parse("https://media.example:8920/Videos/x")), ByteArray(0),
    )

    @Test fun a401IsUnauthorisedAndAnyOtherStatusIsHttpOther() {
        assertEquals(EngineFailure.HTTP_UNAUTHORISED, MediaItems.failureOf(error(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, badStatus(401))))
        assertEquals(EngineFailure.HTTP_OTHER, MediaItems.failureOf(error(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, badStatus(500))))
    }

    @Test fun aStaleRouteRefusalIsFoundInTheCauseChainWhateverTheCode() {
        // As OkHttpDataSource wraps an enqueue failure: IOException(ExecutionException(BlockedRequestException)).
        val stale = IOException(ExecutionException(BlockedRequestException(AppError.of(AppError.Kind.OFFLINE))))
        assertEquals(EngineFailure.ROUTE_STALE, MediaItems.failureOf(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, stale)))
        val signedOut = IOException(BlockedRequestException(AppError.of(AppError.Kind.NOT_SIGNED_IN)))
        assertEquals(EngineFailure.NOT_SIGNED_IN, MediaItems.failureOf(error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, signedOut)))
        val forbidden = IOException(BlockedRequestException(AppError.of(AppError.Kind.FORBIDDEN)))
        assertEquals("a refused target is not retried", EngineFailure.OTHER, MediaItems.failureOf(error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, forbidden)))
    }
}
