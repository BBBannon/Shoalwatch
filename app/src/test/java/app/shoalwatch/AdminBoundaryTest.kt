package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Server admin boundary, by source scan (as PlaybackBoundaryTest). Screens cannot reach tokens, URLs, `ServerApi`, the admin
 * wire types, OkHttp or an Intent URI; nothing admin is saved anywhere, logged or printed; the wire types never declare the fields
 * that carry secrets or addresses; every admin model hides itself from logs; every admin screen draws through AdminScaffold (so
 * FLAG_SECURE cannot be forgotten); and no admin route is left on a stand-in. Failures name the file and line.
 */
class AdminBoundaryTest {
    private val uiBanned = listOf(
        "app.shoalwatch.data.admin.api", "AdminGateway", "UserPolicyEdit", "ServerApi", "import okhttp3.", "toHttpUrl", "Intent.ACTION_VIEW", "Uri.parse",
    )

    private val adminBanned = listOf(
        "SharedPreferences", "getSharedPreferences", "DataStore", "@Dao", "@Entity", "RoomDatabase", "SavedStateHandle", "ClipboardManager",
        "NotificationCompat", "File(", "openFileOutput", "println", "NotBuiltYet",
    )

    /** `Log.` is banned in admin code unless it is `AppLog.` (which logs by kind or a fixed word only). */
    private val rawLog = Regex("(?<![A-Za-z])Log\\.")

    private val undeclared = listOf(
        "AccessToken", "RemoteEndPoint", "LocalAddress", "LogPath", "ProgramDataPath", "CachePath", "WebPath", "Locations", "IconUrl",
        "LongErrorMessage", "Overview",
    )

    private val root = listOf("src/main/java", "src/main/kotlin").map(::File).filter { it.isDirectory }

    private fun sources(): List<Pair<String, List<String>>> = root
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        .map { it.path.replace(File.separatorChar, '/') to it.readLines() }

    private fun hits(files: List<Pair<String, List<String>>>, where: (String) -> Boolean, bad: (String) -> Boolean): List<String> =
        files.filter { where(it.first) }.flatMap { (path, lines) ->
            lines.withIndex().filter { (_, line) -> bad(line) }.map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
        }

    internal fun uiViolations(files: List<Pair<String, List<String>>>) =
        hits(files, { "/app/shoalwatch/ui/" in it }) { line -> uiBanned.any { it in line } }

    internal fun storageViolations(files: List<Pair<String, List<String>>>) =
        hits(files, { "/app/shoalwatch/data/admin/" in it || "/app/shoalwatch/ui/admin/" in it }) { line ->
            adminBanned.any { it in line } || rawLog.containsMatchIn(line)
        }

    internal fun dtoViolations(path: String, lines: List<String>) =
        hits(listOf(path to lines), { true }) { line -> undeclared.any { "@SerialName(\"$it\")" in line } }

    /** Each non-enum class in [lines] must have `override fun toString` before the next declaration starts. */
    internal fun unredacted(path: String, lines: List<String>): List<String> {
        val declaration = Regex("^\\s*(?:(?:data|sealed|abstract|open|internal|private)\\s+)*(class|interface|object|enum class)\\s+(\\w+)")
        val starts = lines.withIndex().mapNotNull { (i, line) -> declaration.find(line)?.let { Triple(i, it.groupValues[1], it.groupValues[2]) } }
        return starts.withIndex().filter { (_, s) -> s.second == "class" && !lines[s.first].contains("enum class") }.mapNotNull { (n, s) ->
            val end = starts.getOrNull(n + 1)?.first ?: lines.size
            val body = lines.subList(s.first, end)
            if (body.any { "override fun toString" in it }) null else "$path:${s.first + 1}: ${s.third} does not override toString"
        }
    }

    internal fun unscaffolded(files: List<Pair<String, List<String>>>) =
        files.filter { (path, _) -> "/app/shoalwatch/ui/admin/" in path && path.endsWith("Screen.kt") }
            .filter { (_, lines) -> lines.none { "AdminScaffold(" in it } }
            .map { (path, _) -> "$path: does not draw through AdminScaffold" }

    @Test fun noScreenReachesTheAdminWireTokensOrAUri() {
        val files = sources()
        assertTrue("scanned too few screens", files.count { "/ui/" in it.first } > 20)
        val found = uiViolations(files)
        assertTrue("A screen reaches the admin wire, OkHttp or a URI:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun nothingAdminIsSavedLoggedOrPrinted() {
        val files = sources()
        assertTrue(files.count { "/ui/admin/" in it.first } >= 20)
        assertTrue(files.count { "/data/admin/" in it.first } >= 8)
        val found = storageViolations(files)
        assertTrue("Admin code saves, logs, prints or is left on a stand-in:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun theWireTypesNeverDeclareSecretsOrAddresses() {
        val dtos = sources().single { it.first.endsWith("/data/admin/api/AdminDtos.kt") }
        val found = dtoViolations(dtos.first, dtos.second)
        assertTrue("A wire type declares a field that must never be decoded:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun everyAdminModelHidesItselfFromLogs() {
        val models = sources().single { it.first.endsWith("/data/admin/AdminModels.kt") }
        assertTrue(models.second.count { Regex("^data class ").containsMatchIn(it) } >= 10)
        val found = unredacted(models.first, models.second)
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    @Test fun everyAdminScreenDrawsThroughTheScaffold() {
        val files = sources()
        assertEquals(7, files.count { "/ui/admin/" in it.first && it.first.endsWith("Screen.kt") })
        val found = unscaffolded(files)
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    @Test fun theScannerCatchesEachRuleAndNamesTheLine() {
        val ui = "src/main/java/app/shoalwatch/ui/admin/Planted.kt"
        for (word in uiBanned) {
            assertEquals(word, listOf("$ui:2: val a = $word"), uiViolations(listOf(ui to listOf("package x", "val a = $word"))))
        }
        for (word in adminBanned + "Log.d(") {
            assertEquals(word, listOf("$ui:2: val a = $word"), storageViolations(listOf(ui to listOf("package x", "val a = $word"))))
        }
        assertTrue("AppLog is allowed", storageViolations(listOf(ui to listOf("AppLog.d(TAG, \"x\")"))).isEmpty())
        assertTrue("outside admin is not this test's business", storageViolations(listOf("src/main/java/app/shoalwatch/data/Foo.kt" to listOf("println()"))).isEmpty())
        val dto = "src/main/java/app/shoalwatch/data/admin/api/AdminDtos.kt"
        for (name in undeclared) {
            assertEquals(name, listOf("$dto:1: @SerialName(\"$name\") val x: String? = null"), dtoViolations(dto, listOf("@SerialName(\"$name\") val x: String? = null")))
        }
        assertTrue("a longer name is not the banned one", dtoViolations(dto, listOf("@SerialName(\"OverviewText\") val x: String? = null")).isEmpty())
        val models = "src/main/java/app/shoalwatch/data/admin/AdminModels.kt"
        assertEquals(
            listOf("$models:3: Leaky does not override toString"),
            unredacted(models, listOf("data class Safe(val a: String) {", "    override fun toString() = \"Safe(<redacted>)\"", "data class Leaky(val a: String)", "enum class E { A }")),
        )
        assertEquals(
            listOf("src/main/java/app/shoalwatch/ui/admin/BareScreen.kt: does not draw through AdminScaffold"),
            unscaffolded(listOf("src/main/java/app/shoalwatch/ui/admin/BareScreen.kt" to listOf("@Composable fun BareScreen() { Text(\"x\") }"))),
        )
    }
}
