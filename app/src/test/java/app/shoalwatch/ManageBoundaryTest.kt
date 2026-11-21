package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The library management boundary (Milestone 10), by source scan as AdminBoundaryTest: screens never reach the wire types, the
 * gateway or a third-party picture address; nothing is saved, logged or printed; the wire types never declare a server file path
 * or a third-party picture address; the management endpoints are named only in `ManageGateway`, where every call but delete opens
 * with `gated(`, delete checks the delete right, and every change is sent once and re-checked just before it is written; every
 * management screen draws through `AdminScaffold` (FLAG_SECURE) and opens no raw dialog. A comment line never counts.
 */
class ManageBoundaryTest {
    private val root = listOf("src/main/java", "src/main/kotlin").map(::File).filter { it.isDirectory }

    private fun sources(): List<Pair<String, List<String>>> = root
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        .map { it.path.replace(File.separatorChar, '/') to it.readLines() }

    private fun isComment(line: String) = line.trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private fun hits(files: List<Pair<String, List<String>>>, where: (String) -> Boolean, bad: (String) -> Boolean): List<String> =
        files.filter { where(it.first) }.flatMap { (path, lines) ->
            lines.withIndex().filter { (_, line) -> !isComment(line) && bad(line) }.map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
        }

    private val uiBanned = listOf("app.shoalwatch.data.manage.api", "ManageGateway", "ItemEdit", ".source", "providerIds", "ServerApi")
    private val stored = listOf(
        "SharedPreferences", "DataStore", "@Dao", "@Entity", "SavedStateHandle", "ClipboardManager", "NotificationCompat", "println", "Log.",
    )
    private val undeclared = listOf("Path", "BlurHash", "ImageUrl", "ThumbnailUrl")
    private val managePaths = listOf("RemoteSearch", "/RemoteImages", "MetadataEditor", "/Images/\${", "\"/Items/\$id\"")
    private val rawDialog = Regex("(?<![A-Za-z])(AlertDialog|BasicAlertDialog|Dialog|ModalBottomSheet|Popup)\\(")

    private val sourceField = Regex("""\.source\b""")

    internal fun uiViolations(files: List<Pair<String, List<String>>>) = hits(files, { "/app/shoalwatch/ui/" in it }) { line ->
        uiBanned.filter { it != ".source" }.any { it in line } || sourceField.containsMatchIn(line)
    }

    internal fun storageViolations(files: List<Pair<String, List<String>>>) =
        hits(files, { "/app/shoalwatch/data/manage/" in it || "/app/shoalwatch/ui/manage/" in it }) { line ->
            stored.any { it in line && !line.contains("AppLog.") }
        }

    @Test fun noScreenReachesTheWireOrAThirdPartyAddress() {
        val files = sources()
        assertTrue("the management screens are scanned", files.count { "/ui/manage/" in it.first } >= 8)
        val found = uiViolations(files)
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    @Test fun nothingIsSavedLoggedOrPrinted() {
        val found = storageViolations(sources())
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    @Test fun theWireTypesNeverDeclareAPathOrAThirdPartyPicture() {
        val (path, lines) = sources().single { it.first.endsWith("/data/manage/api/ManageDtos.kt") }
        val found = hits(listOf(path to lines), { true }) { line -> undeclared.any { "@SerialName(\"$it\")" in line } }
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    @Test fun managementEndpointsAreNamedOnlyInTheGateway() {
        val files = sources()
        val gateway = files.single { it.first.endsWith("/data/manage/api/ManageGateway.kt") }
        for (p in managePaths) assertTrue("the gateway names $p", gateway.second.any { p in it })
        val found = hits(files, { !it.endsWith("/data/manage/api/ManageGateway.kt") && "/test/" !in it }) { line ->
            listOf("RemoteSearch/", "/RemoteImages", "/MetadataEditor").any { it in line } && "\"" in line
        }
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    @Test fun everyCallIsGatedAndEveryChangeIsSentOnce() {
        val (path, lines) = sources().single { it.first.endsWith("/data/manage/api/ManageGateway.kt") }
        assertEquals("every Manage member", 12, lines.count { "override suspend fun" in it })
        val found = lines.withIndex().filter { !isComment(it.value) }.flatMap { (i, line) ->
            listOfNotNull(
                "$path:${i + 1}: not gated".takeIf { "override suspend fun" in line && "= gated(" !in line && "fun delete(" !in line && "fun canDelete(" !in line },
                "$path:${i + 1}: a change without sendOnce".takeIf { ("api.post(" in line || "api.delete(" in line) && "sendOnce = true" !in line },
                "$path:${i + 1}: a change not re-checked".takeIf { ("api.post(" in line || "api.delete(" in line || "api.postBytes(" in line) && "sendIf = " !in line },
            )
        }
        assertTrue(found.joinToString("\n"), found.isEmpty())
        assertTrue("delete checks the delete right first", lines.any { "if (!allowsDelete(s))" in it })
        assertTrue("canDelete checks it before asking", lines.any { "allowsDelete(s) && itemCanDelete(" in it })
        assertTrue("delete asks the item's own CanDelete", lines.any { "if (!itemCanDelete(s, id))" in it })
        assertTrue("a refused 401 is checked, not taken as a sign-out", lines.any { "api.delete(" in it && "verifyUnauthorised = true" in it })
    }

    @Test fun everyManagementScreenIsSecureAndOpensNoRawDialog() {
        val files = sources().filter { "/app/shoalwatch/ui/manage/" in it.first }
        val screens = files.filter { it.first.endsWith("Screen.kt") }
        assertEquals("hub, identify, pictures, edit", 4, screens.size)
        for ((path, lines) in screens) assertTrue("$path draws through AdminScaffold", lines.any { "AdminScaffold(" in it && !isComment(it) })
        val raw = hits(files, { true }) { line -> rawDialog.containsMatchIn(line) }
        assertTrue(raw.joinToString("\n"), raw.isEmpty())
    }

    @Test fun theScannerCatchesEachRule() {
        val ui = "src/main/java/app/shoalwatch/ui/manage/Planted.kt"
        for (word in uiBanned) assertEquals(word, 1, uiViolations(listOf(ui to listOf("val a = x$word"))).size)
        for (word in stored) assertEquals(word, 1, storageViolations(listOf(ui to listOf("val a = $word"))).size)
        assertTrue("AppLog is fine", storageViolations(listOf(ui to listOf("AppLog.d(TAG, \"x\")"))).isEmpty())
        assertTrue("a comment is not a use", uiViolations(listOf(ui to listOf("// ManageGateway"))).isEmpty())
    }
}
