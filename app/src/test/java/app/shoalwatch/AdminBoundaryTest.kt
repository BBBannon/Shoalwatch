package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Server admin boundary, by source scan (as PlaybackBoundaryTest). Screens cannot reach tokens, URLs, `ServerApi`, the admin
 * wire types, OkHttp or an Intent URI; nothing admin is saved anywhere, logged or printed; the wire types never declare the fields
 * that carry secrets or addresses; every admin model hides itself from logs; admin endpoints are named only in `AdminGateway`,
 * where every call but `me` is gated and every action is sent once; every admin screen function draws through AdminScaffold (the
 * scaffold holds FLAG_SECURE); every admin dialog or popup is a SecureDialog; every admin route applies AdminRouteEffects
 * (visible-only loading, leaving on a closed gate); and no admin route is left on a stand-in. Failures name the file and line.
 * A comment line never counts as using something.
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

    private fun isComment(line: String) = line.trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private fun uses(lines: List<String>, call: String) = lines.any { call in it && !isComment(it) }

    /**
     * Top-level functions in [lines]: (name, index of the `fun` line, its lines up to the next line that starts in column 0 with
     * anything but a closing bracket).
     */
    private fun topLevelFunctions(lines: List<String>): List<Triple<String, Int, List<String>>> {
        val start = Regex("^(?:(?:private|internal|public)\\s+)?fun\\s+(?:[\\w.<>]+\\.)?(\\w+)\\(")
        return lines.withIndex().mapNotNull { (i, line) -> start.find(line)?.let { i to it.groupValues[1] } }.map { (i, name) ->
            val end = (i + 1 until lines.size).firstOrNull { j -> lines[j].isNotEmpty() && !lines[j][0].isWhitespace() && lines[j][0] != '}' && lines[j][0] != ')' }
            Triple(name, i, lines.subList(i, end ?: lines.size))
        }
    }

    /** Every `*Screen.kt` in ui/admin, and every `fun …Screen(` in ui/admin, must call `AdminScaffold(` (not in a comment). */
    internal fun unscaffolded(files: List<Pair<String, List<String>>>): List<String> {
        val admin = files.filter { (path, _) -> "/app/shoalwatch/ui/admin/" in path }
        val wholeFiles = admin.filter { (path, lines) -> path.endsWith("Screen.kt") && !uses(lines, "AdminScaffold(") }
            .map { (path, _) -> "$path: does not draw through AdminScaffold" }
        val screens = admin.flatMap { (path, lines) ->
            topLevelFunctions(lines).filter { (name, _, body) -> name.endsWith("Screen") && !uses(body, "AdminScaffold(") }
                .map { (name, i, _) -> "$path:${i + 1}: $name does not draw through AdminScaffold" }
        }
        return wholeFiles + screens
    }

    /** Admin endpoints as they appear in a path literal. Playback's `/Sessions/Playing…` reports are not admin. */
    private val adminPaths = listOf(
        "\"/System/Restart", "\"/System/Shutdown", "\"/System/ActivityLog", "\"/Library/Refresh", "\"/Library/VirtualFolders",
        "\"/ScheduledTasks", "\"/Devices", "/Policy\"", "/Refresh\"", "\"/Sessions\"", "\"/Sessions/$", "\"/Users\"",
    )

    internal fun adminPathsOutsideTheGateway(files: List<Pair<String, List<String>>>) =
        // Milestone 10: the library management gateway names `/Items/{id}/Refresh` too, under the same rules (ManageBoundaryTest).
        hits(files, { !it.endsWith("/data/admin/api/AdminGateway.kt") && !it.endsWith("/data/manage/api/ManageGateway.kt") }) { line -> !isComment(line) && adminPaths.any { it in line } }

    /**
     * In AdminGateway.kt: every POST/DELETE says `sendOnce = true` and asks `sendIf` (the gate, again, just before writing), and
     * every `override suspend fun` opens with `gated(`.
     */
    internal fun ungatedOrResent(path: String, lines: List<String>): List<String> = lines.withIndex().filter { !isComment(it.value) }.flatMap { (i, line) ->
        listOfNotNull(
            "$path:${i + 1}: an action without sendOnce = true".takeIf { ("api.post(" in line || "api.delete(" in line) && "sendOnce = true" !in line },
            "$path:${i + 1}: an action not re-checked before it is written".takeIf { ("api.post(" in line || "api.delete(" in line) && "sendIf = " !in line },
            "$path:${i + 1}: an admin call that is not gated".takeIf { "override suspend fun" in line && "= gated(" !in line },
        )
    }

    private val rawDialog = Regex("(?<![A-Za-z])(AlertDialog|BasicAlertDialog|Dialog|ModalBottomSheet|Popup|DropdownMenu)\\(")

    /** A raw dialog or popup window in ui/admin: only in AdminDialogs.kt, and there only with `properties = SecureDialog`. */
    internal fun insecureDialogs(files: List<Pair<String, List<String>>>): List<String> =
        files.filter { "/app/shoalwatch/ui/admin/" in it.first }.flatMap { (path, lines) ->
            lines.withIndex().filter { (_, line) -> !isComment(line) && rawDialog.containsMatchIn(line) }.mapNotNull { (i, line) ->
                val secure = path.endsWith("/ui/admin/AdminDialogs.kt") && lines.subList(i, minOf(lines.size, i + 4)).any { "properties = SecureDialog" in it }
                if (secure) null else "$path:${i + 1}: ${line.trim()} is not a SecureDialog"
            }
        }

    private val mappedRoute = Regex("^\\s*\\w+ = \\{ (\\w+Route)\\(graph, nav\\) \\},?$")

    /** Every route function the real admin graph maps (`x = { XRoute(graph, nav) }` in AdminNav.kt) calls `AdminRouteEffects(`. */
    internal fun routesWithoutEffects(path: String, lines: List<String>): List<String> {
        val functions = topLevelFunctions(lines).associateBy { it.first }
        return lines.mapNotNull { mappedRoute.find(it)?.groupValues?.get(1) }.mapNotNull { name ->
            val f = functions[name] ?: return@mapNotNull "$path: $name is mapped but not defined here"
            if (uses(f.third, "AdminRouteEffects(")) null else "$path:${f.second + 1}: $name does not apply AdminRouteEffects"
        }
    }

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

    @Test fun adminEndpointsAreNamedOnlyInTheGateway() {
        val files = sources()
        val gateway = files.single { it.first.endsWith("/data/admin/api/AdminGateway.kt") }
        for (p in adminPaths) assertTrue("the gateway names $p", gateway.second.any { p in it })
        val found = adminPathsOutsideTheGateway(files)
        assertTrue("An admin endpoint is named outside AdminGateway:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun everyGatewayCallIsGatedAndEveryActionIsSentOnce() {
        val (path, lines) = sources().single { it.first.endsWith("/data/admin/api/AdminGateway.kt") }
        assertEquals("every Admin member", 17, lines.count { "override suspend fun" in it })
        assertEquals("the ten actions go through send", 10, lines.count { Regex("(?<![\\w.])send\\(s, \"(POST|DELETE)\"").containsMatchIn(it) })
        assertEquals("which alone calls POST and DELETE", 2, lines.count { "api.post(" in it || "api.delete(" in it })
        val found = ungatedOrResent(path, lines)
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    @Test fun everyAdminDialogIsASecureDialog() {
        val files = sources()
        assertTrue("the one dialog is scanned", files.single { it.first.endsWith("/ui/admin/AdminDialogs.kt") }.second.any { rawDialog.containsMatchIn(it) })
        val found = insecureDialogs(files)
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    @Test fun everyAdminRouteAppliesTheRouteEffects() {
        val (path, lines) = sources().single { it.first.endsWith("/ui/admin/AdminNav.kt") }
        assertEquals("seven routes", 7, lines.count { Regex("^\\s*composable\\(AdminRoutes\\.").containsMatchIn(it) })
        assertEquals("each mapped to a route function", 7, lines.count { mappedRoute.containsMatchIn(it) })
        val found = routesWithoutEffects(path, lines)
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
        val bare = "src/main/java/app/shoalwatch/ui/admin/BareScreen.kt"
        assertEquals(
            listOf("$bare: does not draw through AdminScaffold", "$bare:2: BareScreen does not draw through AdminScaffold"),
            unscaffolded(listOf(bare to listOf("@Composable", "fun BareScreen() { Text(\"x\") }"))),
        )
        val commented = "src/main/java/app/shoalwatch/ui/admin/CommentScreen.kt"
        assertEquals("a comment is not a call", 2, unscaffolded(listOf(commented to listOf("// AdminScaffold(", "fun CommentScreen() {", "    /* AdminScaffold( */", "}"))).size)
        val two = "src/main/java/app/shoalwatch/ui/admin/TwoScreen.kt"
        assertEquals(
            listOf("$two:5: SecondScreen does not draw through AdminScaffold"),
            unscaffolded(listOf(two to listOf("@Composable", "fun TwoScreen() {", "    AdminScaffold(x) {}", "}", "fun SecondScreen() {", "    Text(\"x\")", "}"))),
        )
        val elsewhere = "src/main/java/app/shoalwatch/data/servers/Planted.kt"
        for (p in adminPaths) {
            assertEquals(p, listOf("$elsewhere:1: api.post(s, ${p}x\")"), adminPathsOutsideTheGateway(listOf(elsewhere to listOf("api.post(s, ${p}x\")"))))
        }
        assertTrue("playback reports are not admin", adminPathsOutsideTheGateway(listOf(elsewhere to listOf("api.post(s, \"/Sessions/Playing/Progress\")"))).isEmpty())
        val gw = "src/main/java/app/shoalwatch/data/admin/api/AdminGateway.kt"
        assertEquals(
            listOf(
                "$gw:2: an action without sendOnce = true", "$gw:2: an action not re-checked before it is written", "$gw:3: an admin call that is not gated",
                "$gw:4: an action without sendOnce = true", "$gw:4: an action not re-checked before it is written", "$gw:5: an action not re-checked before it is written",
            ),
            ungatedOrResent(gw, listOf(
                "    override suspend fun restart(s: Session) = gated(s, \"restart\") {",
                "        api.post(s, \"/System/Restart\", retry = false)",
                "    override suspend fun shutDown(s: Session) = logged(\"shutDown\") {",
                "        api.delete(s, \"/Devices\", retry = false)",
                "        api.post(s, \"/x\", retry = false, sendOnce = true)",
                "        api.post(s, \"/x\", retry = false, sendOnce = true, sendIf = stillAllowed)",
            )),
        )
        val raw = "src/main/java/app/shoalwatch/ui/admin/RawScreen.kt"
        for (call in listOf("AlertDialog(", "Dialog(", "BasicAlertDialog(", "ModalBottomSheet(", "Popup(", "DropdownMenu(")) {
            assertEquals(call, listOf("$raw:1: $call is not a SecureDialog"), insecureDialogs(listOf(raw to listOf(call))))
        }
        assertTrue("the secure wrappers are fine", insecureDialogs(listOf(raw to listOf("AdminConfirmDialog(", "TypedConfirmDialog(", "MessageDialog("))).isEmpty())
        val dialogs = "src/main/java/app/shoalwatch/ui/admin/AdminDialogs.kt"
        assertEquals(listOf("$dialogs:1: AlertDialog( is not a SecureDialog"), insecureDialogs(listOf(dialogs to listOf("AlertDialog(", "    onDismissRequest = onDismiss,"))))
        assertTrue(insecureDialogs(listOf(dialogs to listOf("AlertDialog(", "    onDismissRequest = onDismiss,", "    properties = SecureDialog,"))).isEmpty())
        val nav = "src/main/java/app/shoalwatch/ui/admin/AdminNav.kt"
        assertEquals(
            listOf("$nav:8: BRoute does not apply AdminRouteEffects", "$nav: CRoute is mapped but not defined here"),
            routesWithoutEffects(nav, listOf(
                "    a = { ARoute(graph, nav) },", "    b = { BRoute(graph, nav) },", "    c = { CRoute(graph, nav) },",
                "@Composable", "private fun ARoute(graph: AppGraph, nav: NavHostController) {", "    AdminRouteEffects(vm, gate, nav)", "}",
                "private fun BRoute(graph: AppGraph, nav: NavHostController) {", "    // AdminRouteEffects(vm, gate, nav)", "}",
            )),
        )
    }
}
