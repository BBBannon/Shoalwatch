package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Screens cannot reach media routes, OkHttp, the playback wire types or Media3's data sources: a screen holding a route or a
 * client could send with the account's token outside the guarded path. Applies to every file under `ui/`. Failures name the
 * file and line.
 */
class PlaybackBoundaryTest {
    private val banned = listOf(
        "MediaRoute", "GuardedCallFactory", "import okhttp3.", "app.shoalwatch.data.playback.api", "app.shoalwatch.data.api.",
        "androidx.media3.datasource",
    )

    private fun sources() = listOf("src/main/java", "src/main/kotlin").map(::File).filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun violations(files: List<Pair<String, List<String>>>): List<String> =
        files.filter { "/ui/" in it.first.replace(File.separatorChar, '/') }.flatMap { (path, lines) ->
            lines.withIndex().filter { (_, line) -> banned.any { it in line } }.map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
        }

    @Test fun noScreenReachesARouteOkHttpOrThePlaybackWireTypes() {
        val files = sources()
        val screens = files.count { "/ui/" in it.path.replace(File.separatorChar, '/') }
        assertTrue("scanned only $screens screen files", screens > 20)
        val found = violations(files.map { it.path to it.readLines() })
        assertTrue("A screen reaches the media path:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun theScannerCatchesEachBannedWordAndNamesTheLine() {
        for (word in banned) {
            val found = violations(listOf("src/main/java/app/shoalwatch/ui/player/PlayerScreen.kt" to listOf("package x", "val a = $word", "val b = 1")))
            assertEquals(word, listOf("src/main/java/app/shoalwatch/ui/player/PlayerScreen.kt:2: val a = $word"), found)
        }
        assertTrue(violations(listOf("src/main/java/app/shoalwatch/data/playback/PlaybackResolver.kt" to listOf("import app.shoalwatch.data.api.MediaRoute"))).isEmpty())
    }
}
