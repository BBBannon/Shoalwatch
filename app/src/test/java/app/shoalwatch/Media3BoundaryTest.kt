package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Media3 stays inside the engine: only `data/playback/engine/` may name ExoPlayer, Media3's data sources, its subtitle
 * parsers and cues, its media session or its UI (plus the one logging switch in `ShoalwatchApp.kt`), and nothing anywhere may use a Media3 data source other than
 * `OkHttpDataSource` over a route's guarded call factory (`DefaultHttpDataSource` and friends would send without the account's
 * client, pin or guard) or Media3's own `EventLogger` (it logs URIs), and nothing may hand a subtitle file to Media3's text
 * parsers or `Html.fromHtml`. Failures name the file and line.
 */
class Media3BoundaryTest {
    private val outsideEngine = listOf(
        "import androidx.media3.exoplayer", "import androidx.media3.datasource", "import androidx.media3.session", "import androidx.media3.ui",
        "import androidx.media3.extractor", "import androidx.media3.common.text",
    )
    private val nowhere = listOf(
        "DefaultHttpDataSource", "DefaultDataSource", "CacheDataSource", "FileDataSource", "EventLogger", "setWakeMode",
        // Ways a default HTTP source comes back in (Task 6 review M-8): a media source factory over the context, the stock
        // per-format factories, and the stock bitmap loader (built over a default data source).
        "DefaultMediaSourceFactory(appContext", "DefaultMediaSourceFactory(context", "HlsMediaSource.Factory", "ProgressiveMediaSource.Factory",
        "DataSourceBitmapLoader",
        // Untrusted subtitle files are parsed by the app's own linear parsers, never by Media3's text parsers or Android's HTML
        // parser (final-fixes review I-1, I-2): each did quadratic work on a crafted file that nothing could stop.
        "import androidx.media3.extractor.text", "SsaParser", "SubripParser", "WebvttParser", "SubtitleParser", "Html.fromHtml", "fromHtml(",
        // Nor by them inside a played file (subtitle-parsers review I-1): no default parser factory, no extractors factory or
        // switch that would bring one back, no legacy text decoding in the renderer.
        "DefaultSubtitleParserFactory", "DefaultExtractorsFactory", "ParseSubtitlesDuringExtraction", "TextTrackTranscoding",
        "LegacyDecodingEnabled", "SubtitleDecoderFactory", "Tx3gParser", "TtmlParser", "Mp4WebvttParser",
    )

    /**
     * The one narrow exception: `engine/PictureSubtitles.kt` is the player's subtitle parser factory, so it must name Media3's
     * `SubtitleParser` interface and import from `extractor.text` (only the PGS, VobSub and DVB parsers). Every other banned
     * word, the text parsers among them, is still banned there.
     */
    private val pictureParsers = "/data/playback/engine/PictureSubtitles.kt"
    private val allowedForPictureParsers = setOf("import androidx.media3.extractor.text", "SubtitleParser")

    private fun sources() = listOf("src/main/java", "src/main/kotlin").map(::File).filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun violations(files: List<Pair<String, List<String>>>): List<String> = files.flatMap { (path, lines) ->
        val inEngine = "/data/playback/engine/" in path.replace('\\', '/')
        val banned = if (path.replace('\\', '/').endsWith(pictureParsers)) nowhere - allowedForPictureParsers else nowhere
        lines.withIndex().filter { (_, raw) ->
            // Handing the media source factory the picture parsers is the one allowed use of the name.
            val line = raw.replace(PICTURES_ONLY, "")
            banned.any { it in line } || (!inEngine && outsideEngine.any { it in line })
        }
            .map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
    }

    @Test fun media3StaysInTheEngineAndHasNoOtherDataSource() {
        val files = sources()
        assertTrue("scanned only ${files.size} files", files.size > 50)
        assertTrue("the engine is where it should be", files.any { it.path.replace('\\', '/').endsWith("/data/playback/engine/ExoPlayerEngine.kt") })
        val found = violations(files.map { it.path to it.readLines() })
        assertTrue("Media3 outside the engine, or a forbidden data source:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** What is built must say where its bytes come from: the player's media sources and the session's artwork (M-5, M-8). */
    @Test fun everyPlayerAndSessionNamesItsOwnSources() {
        val text = sources().joinToString("\n") { it.readText() }
        fun built(start: String): List<String> = Regex(Regex.escape(start)).findAll(text).map { m ->
            val end = text.indexOf(".build()", m.range.first)
            text.substring(m.range.first, if (end < 0) text.length else end)
        }.toList()
        val players = built("ExoPlayer.Builder(")
        val sessions = built("MediaSession.Builder(")
        assertTrue("the engine builds a player and a session", players.isNotEmpty() && sessions.isNotEmpty())
        for (b in players) assertTrue("a player without the engine's media source factory: $b", ".setMediaSourceFactory(mediaSources)" in b)
        for (b in sessions) assertTrue("a media session without the loader that loads nothing: $b", ".setBitmapLoader(" in b)
        fun count(word: String) = Regex(Regex.escape(word)).findAll(text).count()
        assertEquals("every media source factory is over OkHttpDataSource", count("DefaultMediaSourceFactory("), count("DefaultMediaSourceFactory(OkHttpDataSource.Factory("))
        assertEquals("one media source factory, the engine's", 1, count("DefaultMediaSourceFactory("))
        assertEquals("every media source factory is given only the picture parsers", count("DefaultMediaSourceFactory("), count(PICTURES_ONLY))
        assertEquals("no other subtitle parser factory is set anywhere", count(".setSubtitleParserFactory("), count(PICTURES_ONLY))
    }

    @Test fun theScannerCatchesEachWordAndNamesTheLine() {
        val screen = "src/main/java/app/shoalwatch/ui/player/PlayerScreen.kt"
        val engine = "src/main/java/app/shoalwatch/data/playback/engine/ExoPlayerEngine.kt"
        for (word in outsideEngine) {
            assertEquals(word, listOf("$screen:2: $word.X"), violations(listOf(screen to listOf("package x", "$word.X"))))
            assertTrue(word, violations(listOf(engine to listOf("$word.X"))).isEmpty())
        }
        for (word in nowhere) assertEquals(word, listOf("$engine:1: val a = $word"), violations(listOf(engine to listOf("val a = $word"))))
        val pictures = "src/main/java/app/shoalwatch$pictureParsers"
        for (word in nowhere) {
            val found = violations(listOf(pictures to listOf("val a = $word")))
            if (word in allowedForPictureParsers) assertTrue(word, found.isEmpty()) else assertEquals(word, listOf("$pictures:1: val a = $word"), found)
        }
        assertTrue(violations(listOf(engine to listOf("val s = f$PICTURES_ONLY"))).isEmpty())
        assertEquals(1, violations(listOf(engine to listOf("val s = f.setSubtitleParserFactory(Other)"))).size)
    }

    private companion object {
        const val PICTURES_ONLY = ".setSubtitleParserFactory(PictureSubtitles)"
    }
}
