package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Media3 stays inside the engine: only `data/playback/engine/` may name ExoPlayer, Media3's data sources, its media session
 * or its UI (plus the one logging switch in `ShoalwatchApp.kt`), and nothing anywhere may use a Media3 data source other than
 * `OkHttpDataSource` over a route's guarded call factory (`DefaultHttpDataSource` and friends would send without the account's
 * client, pin or guard) or Media3's own `EventLogger` (it logs URIs). Failures name the file and line.
 */
class Media3BoundaryTest {
    private val outsideEngine = listOf("import androidx.media3.exoplayer", "import androidx.media3.datasource", "import androidx.media3.session", "import androidx.media3.ui")
    private val nowhere = listOf("DefaultHttpDataSource", "DefaultDataSource", "CacheDataSource", "FileDataSource", "EventLogger", "setWakeMode")

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
