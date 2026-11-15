package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Media3 stays inside the engine: only `data/playback/engine/` may name ExoPlayer, Media3's data sources, its subtitle
 * parsers and cues, its media session or its UI (plus the one logging switch in `ShoalwatchApp.kt`), and nothing anywhere may use a Media3 data source other than
 * `OkHttpDataSource` over a route's guarded call factory (`DefaultHttpDataSource` and friends would send without the account's
 * client, pin or guard) or Media3's own `EventLogger` (it logs URIs). Failures name the file and line.
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
    )

    private fun sources() = listOf("src/main/java", "src/main/kotlin").map(::File).filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun violations(files: List<Pair<String, List<String>>>): List<String> = files.flatMap { (path, lines) ->
        val inEngine = "/data/playback/engine/" in path.replace('\\', '/')
        lines.withIndex().filter { (_, line) -> nowhere.any { it in line } || (!inEngine && outsideEngine.any { it in line }) }
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
        for (b in players) assertTrue("a player without the guarded media source factory: $b", ".setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(" in b)
        for (b in sessions) assertTrue("a media session without the loader that loads nothing: $b", ".setBitmapLoader(" in b)
        fun count(word: String) = Regex(Regex.escape(word)).findAll(text).count()
        assertEquals("every media source factory is over OkHttpDataSource", count("DefaultMediaSourceFactory("), count("DefaultMediaSourceFactory(OkHttpDataSource.Factory("))
    }

    @Test fun theScannerCatchesEachWordAndNamesTheLine() {
        val screen = "src/main/java/app/shoalwatch/ui/player/PlayerScreen.kt"
        val engine = "src/main/java/app/shoalwatch/data/playback/engine/ExoPlayerEngine.kt"
        for (word in outsideEngine) {
            assertEquals(word, listOf("$screen:2: $word.X"), violations(listOf(screen to listOf("package x", "$word.X"))))
            assertTrue(word, violations(listOf(engine to listOf("$word.X"))).isEmpty())
        }
        for (word in nowhere) assertEquals(word, listOf("$engine:1: val a = $word"), violations(listOf(engine to listOf("val a = $word"))))
    }
}
