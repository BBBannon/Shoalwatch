package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The download code's boundaries, as a source scan of `src/main` (as [ArtworkBoundaryTest]). Failures name the file and line.
 *  1. Nothing under `ui/` names `DownloadStore`, a DAO, `DownloadPaths`, `File`, `java.io`, `MediaRoute` or `WorkManager`:
 *     screens reach downloads only through the `Downloads` seam.
 *  2. Under `data/downloads`, only `DownloadPaths.kt` builds a `File(`: every download path is made and checked in one place.
 *  3. Outside `net/HttpClientFactory.kt` nothing builds an OkHttp client: no `OkHttpClient.Builder`, `OkHttpClient(` or
 *     `newBuilder()`, except a request's or a URL's own `newBuilder()` (`request.newBuilder()`, `url.newBuilder()`,
 *     `toHttpUrl().newBuilder()`), which copy a request or a URL, never a client.
 *  4. No `Data.Builder` or `setInputData` anywhere: WorkManager's input data stays empty (the queue is in Room).
 *  5. No logging call on `Log` (`Log.v/d/i/w/e/wtf/println`) outside `core/AppLog.kt`; setting a library's log level is not a call.
 */
class DownloadBoundaryTest {
    private class Rule(val name: String, val applies: (String) -> Boolean, val banned: Regex, val allowed: (String) -> Boolean = { false })

    private val rules = listOf(
        Rule("ui reaches downloads only through the Downloads seam", { "/ui/" in it },
            Regex("""\bDownloadStore\b|\b\w*Dao\b|\bDownloadPaths\b|\bFile\b|\bjava\.io\b|\bMediaRoute\b|\bWorkManager\b""")),
        Rule("only DownloadPaths builds a File under data/downloads", { "/data/downloads/" in it && !it.endsWith("/data/downloads/DownloadPaths.kt") },
            Regex("""\bFile\(""")),
        Rule("only HttpClientFactory builds an OkHttp client", { !it.endsWith("/net/HttpClientFactory.kt") },
            Regex("""OkHttpClient\.Builder|\bOkHttpClient\(|newBuilder\(\)"""),
            allowed = { line -> line.replace(Regex("""(\brequest|\burl|toHttpUrl\(\))\.newBuilder\(\)"""), "").let { !Regex("""OkHttpClient\.Builder|\bOkHttpClient\(|newBuilder\(\)""").containsMatchIn(it) } }),
        Rule("WorkManager input data stays empty", { true }, Regex("""\bData\.Builder\b|\bsetInputData\b""")),
        Rule("only AppLog logs", { !it.endsWith("/core/AppLog.kt") }, Regex("""\bLog\.(v|d|i|w|e|wtf|println)\(""")),
    )

    private fun sources() = listOf("src/main/java", "src/main/kotlin").map(::File).filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** Every `rule: path:line: text` where a rule's banned word appears in a file it applies to. */
    private fun violations(files: List<Pair<String, List<String>>>): List<String> = files.flatMap { (raw, lines) ->
        val path = raw.replace('\\', '/')
        rules.filter { it.applies(path) }.flatMap { rule ->
            lines.withIndex().filter { (_, line) -> rule.banned.containsMatchIn(line) && !rule.allowed(line) }
                .map { (i, line) -> "${rule.name}: $path:${i + 1}: ${line.trim()}" }
        }
    }

    @Test fun theDownloadCodeKeepsItsBoundaries() {
        val files = sources()
        assertTrue("scanned only ${files.size} files", files.size > 100)
        assertTrue("the scan reaches data/downloads", files.any { it.path.replace('\\', '/').endsWith("/data/downloads/DownloadPaths.kt") })
        val found = violations(files.map { it.path to it.readLines() })
        assertTrue("download boundaries crossed:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun eachRuleCatchesItsWordsAndNamesTheLine() {
        val ui = "src/main/java/app/shoalwatch/ui/downloads/Screen.kt"
        for (word in listOf("DownloadStore", "DownloadDao", "OfflineLibraryDao", "DownloadPaths", "File", "java.io.File", "MediaRoute", "WorkManager")) {
            assertEquals(word, 1, violations(listOf(ui to listOf("package x", "val a = $word"))).size)
        }
        assertTrue("words that only contain a banned one pass", violations(listOf(ui to listOf("val profile = Profiles()", "// Filed under"))).isEmpty())

        val data = "src/main/java/app/shoalwatch/data/downloads/Fetcher.kt"
        assertEquals(listOf("only DownloadPaths builds a File under data/downloads: $data:2: val f = File(dir, name)"),
            violations(listOf(data to listOf("package x", "val f = File(dir, name)"))))
        assertTrue(violations(listOf("src/main/java/app/shoalwatch/data/downloads/DownloadPaths.kt" to listOf("File(dir, name)"))).isEmpty())
        assertTrue("other data packages are not this rule's", violations(listOf("src/main/java/app/shoalwatch/data/images/Pinned.kt" to listOf("File(dir, name)"))).isEmpty())

        val any = "src/main/java/app/shoalwatch/data/servers/Gateway.kt"
        for (line in listOf("OkHttpClient.Builder()", "val c = OkHttpClient()", "client.newBuilder().build()", "base.newBuilder()")) {
            assertEquals(line, 1, violations(listOf(any to listOf(line))).size)
        }
        for (line in listOf("request.newBuilder().url(u)", "url.newBuilder().build()", "address.toHttpUrl().newBuilder()")) {
            assertTrue(line, violations(listOf(any to listOf(line))).isEmpty())
        }
        assertEquals("a request's copy does not hide a client's on the same line", 1,
            violations(listOf(any to listOf("request.newBuilder(); client.newBuilder()"))).size)
        assertTrue(violations(listOf("src/main/java/app/shoalwatch/net/HttpClientFactory.kt" to listOf("OkHttpClient.Builder()", "base.newBuilder()"))).isEmpty())

        for (line in listOf("Data.Builder().putString(\"k\", v)", "builder.setInputData(data)")) {
            assertEquals(line, 1, violations(listOf("src/main/java/app/shoalwatch/AppGraph.kt" to listOf(line))).size)
        }

        for (line in listOf("Log.d(TAG, msg)", "android.util.Log.w(TAG, msg)", "Log.e(TAG, msg, e)", "Log.println(4, TAG, msg)")) {
            assertEquals(line, 1, violations(listOf("src/main/java/app/shoalwatch/data/X.kt" to listOf(line))).size)
        }
        assertTrue("AppLog itself and a level setting pass", violations(listOf(
            "src/main/java/app/shoalwatch/core/AppLog.kt" to listOf("Log.w(tag, msg)"),
            "src/main/java/app/shoalwatch/ShoalwatchApp.kt" to listOf("Log.setLogLevel(Log.LOG_LEVEL_OFF)", "AppLog.w(TAG, msg)"),
        )).isEmpty())
    }
}
