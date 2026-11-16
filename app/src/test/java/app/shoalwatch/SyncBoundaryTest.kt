package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sync-back code's boundaries (Task 16), as a source scan of `src/main` (as [DownloadBoundaryTest]). Failures name the file
 * and line. Comments are blanked first (line numbers kept), so a KDoc that says what is never sent is not itself a use; string
 * literals are code and are scanned.
 *  1. Nothing under `ui/` names `OutboxStore`, `OutboxDao`, `SyncSender`, `SyncGateway`, `ReleaseStore`, `HousekeepingDao`,
 *     `LocalPlaying` or `WorkManager`: screens reach sync and housekeeping only through their seams.
 *  2. In `data/sync`, only `api/SyncGateway.kt` (`SyncGatewayImpl`) names `/UserItems/` with `UserData` (also split across lines).
 *  3. `datePlayed` appears nowhere in `src/main`: a dated mark adds one to the count on every call, so it is never sent.
 *  4. Nothing in `data/sync` names `/Sessions/Playing`: session reports are never replayed.
 *  5. No `Data.Builder` or `setInputData` anywhere: WorkManager's input data stays empty.
 *  6. Only `OutboxStore` writes `sync_outbox` or `sync_marks`: SQL `INSERT`/`DELETE`/`UPDATE` on them appears only in
 *     `OutboxDao.kt` (and `Migration4To5.kt`), and outside `OutboxStore.kt` (and `AppDatabase.kt`, which declares it)
 *     `outboxDao()` is only ever a direct call of one of its read methods; the write methods are found by reading `OutboxDao.kt`.
 */
class SyncBoundaryTest {
    private class Rule(val name: String, val applies: (String) -> Boolean, val banned: Regex, val allowed: (String) -> Boolean = { false }, val across: Regex? = null)

    private val outboxDaoFile = File("src/main/java/app/shoalwatch/data/db/sync/OutboxDao.kt")

    private fun rules(writes: Set<String>) = listOf(
        Rule("ui reaches sync only through its seams", { "/ui/" in it },
            Regex("""\b(OutboxStore|OutboxDao|SyncSender|SyncGateway|ReleaseStore|HousekeepingDao|LocalPlaying|WorkManager)\b""")),
        Rule("only SyncGatewayImpl names the user-data route in data/sync", { "/data/sync/" in it && !it.endsWith("/data/sync/api/SyncGateway.kt") },
            Regex("""/UserItems/.*UserData"""), across = Regex("""/UserItems/[^;]{0,120}?UserData""")),
        Rule("datePlayed is never sent", { true }, Regex("""\bdatePlayed\b""")),
        Rule("session reports are never replayed by sync", { "/data/sync/" in it }, Regex("""/Sessions/Playing""")),
        Rule("WorkManager input data stays empty", { true }, Regex("""\bData\.Builder\b|\bsetInputData\b""")),
        Rule("only OutboxDao holds SQL that writes the outbox", { !it.endsWith("/data/db/sync/OutboxDao.kt") && !it.endsWith("/data/db/Migration4To5.kt") },
            OUTBOX_WRITE_SQL, across = OUTBOX_WRITE_SQL),
        Rule("only OutboxStore calls OutboxDao's writes", { !it.endsWith("/data/sync/OutboxStore.kt") && !it.endsWith("/data/db/AppDatabase.kt") },
            Regex("""\boutboxDao\(\)"""),
            allowed = { line -> line.replace(Regex("""\boutboxDao\(\)\s*\.\s*(\w+)\s*\(""")) { m -> if (m.groupValues[1] in writes) m.value else "" }.let { "outboxDao()" !in it } }),
    )

    private companion object {
        val OUTBOX_WRITE_SQL = Regex("""\b(INSERT\s+(OR\s+\w+\s+)?INTO|DELETE\s+FROM|UPDATE(\s+OR\s+\w+)?)\s+`?(sync_outbox|sync_marks)\b""")
        val WRITE_ANNOTATION = Regex("""@(Insert|Upsert|Update|Delete)\b[^\n]*\n(\s*@[^\n]*\n)*[^\n]*?\bfun\s+(\w+)""")
        val WRITE_QUERY = Regex("""@Query\(\s*"\s*(INSERT|DELETE|UPDATE)\b[\s\S]*?\)\s*\n(\s*@[^\n]*\n)*[^\n]*?\bfun\s+(\w+)""")

        /** [source] with every comment replaced by spaces (newlines kept); strings, raw strings and `${}` templates are followed. */
        fun code(source: String): String {
            val out = StringBuilder(source.length)
            val stack = ArrayDeque<String>() // "q" in a string, "r" in a raw string, "{" in a template's braces
            var i = 0
            fun blank(until: Int) { while (i < until) { out.append(if (source[i] == '\n') '\n' else ' '); i++ } }
            while (i < source.length) {
                val c = source[i]
                val top = stack.lastOrNull()
                val inString = top == "q" || top == "r"
                when {
                    inString && top == "r" && source.startsWith("\"\"\"", i) -> { stack.removeLast(); out.append("\"\"\""); i += 3 }
                    inString && top == "q" && c == '\\' && i + 1 < source.length -> { out.append(c).append(source[i + 1]); i += 2 }
                    inString && top == "q" && c == '"' -> { stack.removeLast(); out.append(c); i++ }
                    inString && source.startsWith("\${", i) -> { stack.addLast("{"); out.append("\${"); i += 2 }
                    inString -> { out.append(c); i++ }
                    source.startsWith("//", i) -> blank(source.indexOf('\n', i).let { if (it < 0) source.length else it })
                    source.startsWith("/*", i) -> blank(source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 })
                    source.startsWith("\"\"\"", i) -> { stack.addLast("r"); out.append("\"\"\""); i += 3 }
                    c == '"' -> { stack.addLast("q"); out.append(c); i++ }
                    c == '\'' && i + 2 < source.length -> { // a char literal, e.g. '"' or '\''
                        val end = if (source[i + 1] == '\\') source.indexOf('\'', i + 3) else i + 2
                        val stop = if (end > 0 && source[end] == '\'') end + 1 else i + 1
                        out.append(source, i, stop); i = stop
                    }
                    c == '{' && top == "{" -> { stack.addLast("{"); out.append(c); i++ }
                    c == '}' && top == "{" -> { stack.removeLast(); out.append(c); i++ }
                    else -> { out.append(c); i++ }
                }
            }
            return out.toString()
        }

        /** The names of `OutboxDao`'s methods that write (a write annotation, or a query that is INSERT, DELETE or UPDATE). */
        fun writeMethods(daoSource: String): Set<String> =
            (WRITE_ANNOTATION.findAll(daoSource) + WRITE_QUERY.findAll(daoSource)).map { it.groupValues.last() }.toSet()
    }

    private fun sources() = listOf("src/main/java", "src/main/kotlin").map(::File).filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** Every `rule: path:line: text` where a rule's banned words appear in the code (comments blanked) of a file it applies to. */
    private fun violations(files: List<Pair<String, String>>, writes: Set<String>): List<String> = files.flatMap { (raw, source) ->
        val path = raw.replace('\\', '/')
        val text = code(source)
        val lines = text.split('\n')
        rules(writes).filter { it.applies(path) }.flatMap { rule ->
            val spanning = rule.across?.findAll(text)?.filter { '\n' in it.value }?.map { m -> text.substring(0, m.range.first).count { it == '\n' } }.orEmpty().toList()
            lines.withIndex().filter { (i, line) -> (rule.banned.containsMatchIn(line) && !rule.allowed(line)) || i in spanning }
                .map { (i, line) -> "${rule.name}: $path:${i + 1}: ${line.trim()}" }
        }.distinct()
    }

    @Test fun theSyncCodeKeepsItsBoundaries() {
        val files = sources()
        assertTrue("scanned only ${files.size} files", files.size > 100)
        assertTrue("the scan reaches data/sync", files.any { it.path.replace('\\', '/').endsWith("/data/sync/api/SyncGateway.kt") })
        val writes = writeMethods(code(outboxDaoFile.readText()))
        assertTrue("OutboxDao's writes found: $writes", writes.containsAll(setOf("insert", "insertMarks", "deleteMarks", "deleteRow", "markSending", "defer")))
        assertTrue("its reads are not writes: $writes", "leftList" !in writes && "rows" !in writes && "hasMark" !in writes)
        val found = violations(files.map { it.path to it.readText() }, writes)
        assertTrue("sync boundaries crossed:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun eachRuleCatchesItsWordsAndNamesTheLine() {
        val writes = setOf("insert", "deleteRow")
        fun v(path: String, vararg lines: String) = violations(listOf(path to lines.joinToString("\n")), writes)

        val ui = "src/main/java/app/shoalwatch/ui/downloads/Screen.kt"
        for (word in listOf("OutboxStore", "OutboxDao", "SyncSender", "SyncGateway", "ReleaseStore", "HousekeepingDao", "LocalPlaying", "WorkManager")) {
            assertEquals(word, 1, v(ui, "package x", "val a = $word").size)
        }
        assertTrue("longer words and comments pass", v(ui, "val s = SyncSenderish()", "// OutboxStore is not reached", "/* WorkManager */ val x = 1").isEmpty())

        val sync = "src/main/java/app/shoalwatch/data/sync/Other.kt"
        assertEquals(listOf("only SyncGatewayImpl names the user-data route in data/sync: $sync:2: api.post(s, \"/UserItems/\$id/UserData\", q, b)"),
            v(sync, "package x", "api.post(s, \"/UserItems/\$id/UserData\", q, b)"))
        assertEquals("split across lines", 1, v(sync, "val p = \"/UserItems/\" +", "    id + \"/UserData\"").size)
        assertTrue("a comment naming the route passes", v(sync, "/** The body of POST /UserItems/{id}/UserData. */").isEmpty())
        assertTrue("SyncGateway.kt may", v("src/main/java/app/shoalwatch/data/sync/api/SyncGateway.kt", "\"/UserItems/\$id/UserData\"").isEmpty())
        assertTrue("other packages are not this rule's", v("src/main/java/app/shoalwatch/data/library/api/LibraryGatewayImpl.kt", "\"/UserItems/\$id/UserData\"").isEmpty())

        val any = "src/main/java/app/shoalwatch/data/library/api/LibraryGatewayImpl.kt"
        assertEquals(1, v(any, "listOf(\"datePlayed\" to date)").size)
        assertTrue("updatePlayed contains the word but is not it", v(any, "dao.updatePlayed(a, b)", "// datePlayed is never sent").isEmpty())

        assertEquals(1, v(sync, "api.post(s, \"/Sessions/Playing/Stopped\", q, b)").size)
        assertTrue("outside data/sync it is the player's", v("src/main/java/app/shoalwatch/data/playback/Reports.kt", "\"/Sessions/Playing\"").isEmpty())

        for (line in listOf("Data.Builder().putString(\"k\", v)", "builder.setInputData(data)")) {
            assertEquals(line, 1, v("src/main/java/app/shoalwatch/AppGraph.kt", line).size)
        }

        val db = "src/main/java/app/shoalwatch/data/db/library/ItemDao.kt"
        for (sql in listOf("DELETE FROM sync_outbox WHERE seq = 1", "UPDATE sync_marks SET leftList = ''", "INSERT OR IGNORE INTO `sync_outbox` (seq) VALUES (1)", "INSERT INTO sync_marks (seq) VALUES (1)")) {
            assertEquals(sql, 1, v(db, "@Query(\"$sql\")").size)
        }
        assertEquals("SQL split across lines", 1, v(db, "@Query(\"DELETE FROM \" +", "\"x\")", "val q = \"\"\"DELETE FROM", "  sync_outbox\"\"\"").size)
        assertTrue("reading or joining them passes", v(db,
            "@Query(\"DELETE FROM user_data WHERE NOT EXISTS (SELECT 1 FROM sync_marks sm WHERE sm.seq = 1)\")",
            "@Query(\"SELECT * FROM sync_outbox\")").isEmpty())
        assertTrue("OutboxDao and the migration may", v("src/main/java/app/shoalwatch/data/db/sync/OutboxDao.kt", "@Query(\"DELETE FROM sync_outbox WHERE seq = :seq\")").isEmpty() &&
            v("src/main/java/app/shoalwatch/data/db/Migration4To5.kt", "\"INSERT INTO sync_outbox (seq) SELECT 1\"").isEmpty())

        val store = "src/main/java/app/shoalwatch/data/db/library/LibraryStore.kt"
        assertTrue("a read through outboxDao() passes", v(store, "val unsent = db.outboxDao().leftList(a, b, c).toSet()").isEmpty())
        for (line in listOf("db.outboxDao().insert(row)", "db.outboxDao() .deleteRow(1)", "val dao = db.outboxDao()", "db.outboxDao().leftList(a); db.outboxDao().insert(r)")) {
            assertEquals(line, 1, v(store, line).size)
        }
        assertTrue("OutboxStore and AppDatabase may", v("src/main/java/app/shoalwatch/data/sync/OutboxStore.kt", "private val dao = db.outboxDao()").isEmpty() &&
            v("src/main/java/app/shoalwatch/data/db/AppDatabase.kt", "abstract fun outboxDao(): OutboxDao").isEmpty())

        val dao = """
            @Dao
            abstract class OutboxDao {
                @Insert
                internal abstract suspend fun insert(row: OutboxEntity): Long

                @Insert(onConflict = OnConflictStrategy.IGNORE)
                internal abstract suspend fun insertMarks(marks: List<SyncMarkEntity>)

                @Query("SELECT * FROM sync_marks WHERE seq = :seq")
                internal abstract suspend fun marksOf(seq: Long): List<SyncMarkEntity>

                @Query(
                    "UPDATE sync_outbox SET sentOnce = 1 WHERE seq = :seq",
                )
                internal abstract suspend fun markSending(seq: Long)

                @Query("DELETE FROM sync_outbox WHERE seq = :seq")
                internal abstract suspend fun deleteRow(seq: Long): Int
            }
        """.trimIndent()
        assertEquals(setOf("insert", "insertMarks", "markSending", "deleteRow"), writeMethods(dao))
    }

    @Test fun commentsAreBlankedButStringsAndLinesAreKept() {
        val src = "val a = \"http://x/*y*/\" // gone\n/* gone\n gone */ val b = 'x'\nval c = \"\${if (p) \"//\" else \"q\"}\" // gone\nval d = \"\"\"// kept\"\"\""
        val out = code(src)
        assertEquals(src.count { it == '\n' }, out.count { it == '\n' })
        assertTrue(out, "http://x/*y*/" in out && "val b = 'x'" in out && "\"//\"" in out && "// kept" in out)
        assertTrue(out, "gone" !in out)
    }
}
