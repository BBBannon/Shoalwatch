package app.shoalwatch.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's minSdk is 29 and Android 10 ships SQLite 3.22. Robolectric and Room's own query verifier use a newer SQLite,
 * so a statement that needs a later one passes every test and then fails on an Android 10 phone. This scans every SQL
 * string in the app's Kotlin sources (and the exported schema) for features newer than 3.22 and names the file and line.
 * Room's `@Upsert` is fine (it is not SQL UPSERT: Room issues an insert, then an update on a constraint failure).
 */
class SqlCompatibilityTest {
    private class Rule(val name: String, val regex: Regex)

    private val rules = listOf(
        Rule("UPSERT (SQLite 3.24)", Regex("ON\\s+CONFLICT\\s*\\(", RegexOption.IGNORE_CASE)),
        Rule("UPSERT clause (SQLite 3.24)", Regex("\\bDO\\s+(UPDATE|NOTHING)\\b", RegexOption.IGNORE_CASE)),
        Rule("RETURNING (3.35)", Regex("\\bRETURNING\\b", RegexOption.IGNORE_CASE)),
        Rule("window function (3.25)", Regex("\\bOVER\\s*\\(", RegexOption.IGNORE_CASE)),
        Rule("RENAME COLUMN (3.25)", Regex("\\bRENAME\\s+COLUMN\\b", RegexOption.IGNORE_CASE)),
        Rule("DROP COLUMN (3.35)", Regex("\\bDROP\\s+COLUMN\\b", RegexOption.IGNORE_CASE)),
        Rule("generated column (3.31)", Regex("\\bGENERATED\\s+ALWAYS\\b", RegexOption.IGNORE_CASE)),
        Rule("STRICT table (3.37)", Regex("\\)\\s*STRICT\\b", RegexOption.IGNORE_CASE)),
        Rule("JSON operator (3.38)", Regex("->>?")),
        Rule("IIF (3.32)", Regex("\\bIIF\\s*\\(", RegexOption.IGNORE_CASE)),
        Rule("MATERIALIZED (3.35)", Regex("\\bMATERIALIZED\\b", RegexOption.IGNORE_CASE)),
        Rule("IS DISTINCT FROM (3.39)", Regex("\\bDISTINCT\\s+FROM\\b", RegexOption.IGNORE_CASE)),
        Rule("aggregate FILTER (3.30)", Regex("\\bFILTER\\s*\\(\\s*WHERE\\b", RegexOption.IGNORE_CASE)),
        Rule("NULLS FIRST/LAST (3.30)", Regex("\\bNULLS\\s+(FIRST|LAST)\\b", RegexOption.IGNORE_CASE)),
        Rule("RIGHT/FULL JOIN (3.39)", Regex("\\b(RIGHT|FULL)\\s+(OUTER\\s+)?JOIN\\b", RegexOption.IGNORE_CASE)),
    )

    /** `UPDATE t SET ... FROM x` (3.33): a FROM at bracket depth 0 after SET and before WHERE. A FROM inside a sub-select is fine. */
    private fun updateFrom(sql: String): List<Int> {
        val found = mutableListOf<Int>()
        for (m in Regex("\\bUPDATE\\s+[\\w`\"]+\\s+SET\\b", RegexOption.IGNORE_CASE).findAll(sql)) {
            var depth = 0
            var i = m.range.last + 1
            while (i < sql.length) {
                when (sql[i]) {
                    '(' -> depth++
                    ')' -> depth--
                    else -> if (depth == 0 && (i == 0 || !sql[i - 1].isLetterOrDigit())) {
                        val rest = sql.substring(i)
                        if (Regex("^WHERE\\b", RegexOption.IGNORE_CASE).containsMatchIn(rest) ||
                            Regex("^(SELECT|INSERT|DELETE|UPDATE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(rest)
                        ) break
                        if (Regex("^FROM\\b", RegexOption.IGNORE_CASE).containsMatchIn(rest)) { found += m.range.first; break }
                    }
                }
                i++
            }
        }
        return found
    }

    /** Every problem in [sql] as `rule` strings with the offset where it starts. */
    private fun problems(sql: String): List<Pair<String, Int>> =
        rules.flatMap { r -> r.regex.findAll(sql).map { r.name to it.range.first }.toList() } + updateFrom(sql).map { "UPDATE ... FROM (3.33)" to it }

    private val stringLiteral = Regex("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)

    /** The text of all string literals in a source file, joined by spaces (so SQL split across `+` still reads as one), with the line each piece started on. */
    private fun literals(source: String): Pair<String, List<Pair<Int, Int>>> {
        val text = StringBuilder()
        val starts = mutableListOf<Pair<Int, Int>>() // offset in text to source line
        for (m in stringLiteral.findAll(source)) {
            starts += text.length to source.substring(0, m.range.first).count { it == '\n' } + 1
            text.append(m.groups[1]?.value ?: m.groups[2]!!.value).append(' ')
        }
        return text.toString() to starts
    }

    private fun lineOf(offset: Int, starts: List<Pair<Int, Int>>) = starts.last { it.first <= offset }.second

    private fun scanSources(root: File): List<String> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val (text, starts) = literals(file.readText())
            problems(text).map { (rule, offset) -> "${file.path}:${lineOf(offset, starts)} uses $rule" }
        }.toList()

    @Test fun theDetectorFlagsEachFeatureAndLeavesPlainSqlAlone() {
        val bad = listOf(
            "INSERT INTO t (a) VALUES (1) ON CONFLICT (a) DO UPDATE SET a = 2",
            "INSERT INTO t (a) VALUES (1) ON CONFLICT DO NOTHING",
            "DELETE FROM t RETURNING a",
            "SELECT row_number() OVER (ORDER BY a) FROM t",
            "ALTER TABLE t RENAME COLUMN a TO b",
            "ALTER TABLE t DROP COLUMN a",
            "CREATE TABLE t (a INTEGER GENERATED ALWAYS AS (1))",
            "CREATE TABLE t (a INTEGER) STRICT",
            "SELECT j -> '\$.a' FROM t",
            "SELECT IIF(a, 1, 2) FROM t",
            "WITH c AS MATERIALIZED (SELECT 1) SELECT * FROM c",
            "UPDATE t SET a = u.b FROM u WHERE u.id = t.id",
            "UPDATE t SET a = 1 FROM u",
            "SELECT * FROM t ORDER BY a NULLS LAST",
            "SELECT * FROM a RIGHT JOIN b ON 1",
        )
        for (sql in bad) assertTrue("should be flagged: $sql", problems(sql).isNotEmpty())
        val fine = listOf(
            "INSERT OR IGNORE INTO t (a) VALUES (1)",
            "UPDATE t SET a = (SELECT b FROM u WHERE u.id = t.id) WHERE id = 1",
            "UPDATE t SET a = CASE WHEN b THEN 1 ELSE 2 END WHERE x = 1",
            "CREATE TABLE IF NOT EXISTS `t` (`a` INTEGER NOT NULL, PRIMARY KEY(`a`), FOREIGN KEY(`a`) REFERENCES `u`(`b`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "DELETE FROM items WHERE rowid IN (SELECT rowid FROM items ORDER BY a LIMIT 3)",
        )
        for (sql in fine) assertEquals("should pass: $sql", emptyList<Pair<String, Int>>(), problems(sql))
        // and the literal finder sees SQL that is split across string pieces
        val (text, _) = literals("val q = \"INSERT INTO t VALUES (1) ON \" +\n \"CONFLICT (a) DO UPDATE SET a = 1\"")
        assertTrue(problems(text).isNotEmpty())
    }

    @Test fun noSourceFileUsesSqlNewerThanSqlite322() {
        val root = File("src/main/java")
        assertTrue("source root ${root.absolutePath} must exist (Gradle runs unit tests in the module directory)", root.isDirectory)
        val scanned = root.walkTopDown().count { it.isFile && it.extension == "kt" }
        assertTrue("only $scanned source files found: the scan would be vacuous", scanned > 20)
        val found = scanSources(root)
        assertTrue("SQL newer than SQLite 3.22 (Android 10) in:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun theExportedSchemaUsesNoSqlNewerThanSqlite322() {
        val dir = File("schemas")
        assertTrue("schema folder ${dir.absolutePath} must exist", dir.isDirectory)
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        assertTrue("no exported schemas found", files.isNotEmpty())
        val found = files.flatMap { file ->
            val db = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
            val sql = db["entities"]!!.jsonArray.flatMap { e ->
                listOf(e.jsonObject["createSql"]!!.jsonPrimitive.content) +
                    (e.jsonObject["indices"]?.jsonArray.orEmpty().map { it.jsonObject["createSql"]!!.jsonPrimitive.content })
            } + db["setupQueries"]!!.jsonArray.map { it.jsonPrimitive.content }
            sql.flatMap { s -> problems(s).map { "${file.path}: ${it.first} in `${s.take(60)}`" } }
        }
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }
}
