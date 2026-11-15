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
 * string in the app's sources (Kotlin and Java, `src/main/java` and `src/main/kotlin`) and the exported schema for features
 * newer than 3.22 and names the file and line.
 *
 * Two kinds of rule. The syntax rules (UPSERT, RETURNING, ...) are rare enough in ordinary strings that they run on every
 * string literal. The function and keyword rules (`floor(`, `format(`, `TRUE`, ...) would hit ordinary text, so they run
 * only on literals that look like SQL (contain `SELECT `, `INSERT `, `UPDATE `, `DELETE `, `CREATE ` or `ALTER `) and on the schema.
 * String pieces joined with `+` are read as one statement. Room's `@Upsert` is fine (it is not SQL UPSERT: Room issues an
 * insert, then an update on a constraint failure).
 */
class SqlCompatibilityTest {
    private class Rule(val name: String, val regex: Regex)

    private fun rule(name: String, pattern: String) = Rule(name, Regex(pattern, RegexOption.IGNORE_CASE))

    /** Syntax that no ordinary string contains: checked everywhere. */
    private val syntaxRules = listOf(
        rule("UPSERT (SQLite 3.24)", "ON\\s+CONFLICT\\s*\\("),
        rule("UPSERT clause (SQLite 3.24)", "\\bDO\\s+(UPDATE|NOTHING)\\b"),
        rule("RETURNING (3.35)", "\\bRETURNING\\b"),
        rule("window function (3.25)", "\\bOVER\\s*\\("),
        rule("RENAME COLUMN (3.25)", "\\bRENAME\\s+COLUMN\\b"),
        rule("DROP COLUMN (3.35)", "\\bDROP\\s+COLUMN\\b"),
        rule("VACUUM INTO (3.27)", "\\bVACUUM\\s+INTO\\b"),
        rule("generated column (3.31)", "\\bGENERATED\\s+ALWAYS\\b"),
        rule("generated column (3.31)", "\\bAS\\s*\\([^)]*\\)\\s*(STORED|VIRTUAL)\\b"),
        rule("STRICT table (3.37)", "\\)\\s*,?\\s*STRICT\\b"),
        rule("STRICT table (3.37)", ",\\s*STRICT\\b"),
        Rule("JSON operator (3.38)", Regex("->>?")),
        rule("IIF (3.32)", "\\bIIF\\s*\\("),
        rule("MATERIALIZED (3.35)", "\\bMATERIALIZED\\b"),
        rule("IS DISTINCT FROM (3.39)", "\\bDISTINCT\\s+FROM\\b"),
        rule("aggregate FILTER (3.30)", "\\bFILTER\\s*\\(\\s*WHERE\\b"),
        rule("NULLS FIRST/LAST (3.30)", "\\bNULLS\\s+(FIRST|LAST)\\b"),
        rule("RIGHT/FULL JOIN (3.39)", "\\b(RIGHT|FULL)\\s+(OUTER\\s+)?JOIN\\b"),
    )

    /** Words and functions that read as plain text elsewhere: checked only in strings that look like SQL, and in the schema. */
    private val sqlOnlyRules = listOf(
        rule("named window (3.25)", "\\bOVER\\s+[A-Za-z_]"),
        rule("WINDOW clause (3.25)", "\\bWINDOW\\s+[A-Za-z_]+\\s+AS\\b"),
        rule("PARTITION BY (3.25)", "\\bPARTITION\\s+BY\\b"),
        rule("TRUE/FALSE literal (3.23)", "\\b(TRUE|FALSE)\\b"),
        rule("IS [NOT] TRUE/FALSE (3.23)", "\\bIS\\s+(NOT\\s+)?(TRUE|FALSE)\\b"),
        rule("math function (3.35)", "\\b(ceil|ceiling|floor|pow|power|sqrt|ln|log|log2|log10|exp|trunc|sign|mod|pi)\\s*\\("),
        rule("unixepoch (3.38)", "\\bunixepoch\\s*\\("),
        rule("format (3.38)", "\\bformat\\s*\\("),
        rule("unhex (3.41)", "\\bunhex\\s*\\("),
        rule("concat (3.44)", "\\bconcat(_ws)?\\s*\\("),
        rule("string_agg (3.44)", "\\bstring_agg\\s*\\("),
        rule("octet_length (3.43)", "\\boctet_length\\s*\\("),
        rule("timediff (3.43)", "\\btimediff\\s*\\("),
    )

    private val looksLikeSql = Regex("\\b(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER)\\s", RegexOption.IGNORE_CASE)

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

    /** Every problem in one statement as `rule` and the offset where it starts. [sql] says whether the function and keyword rules apply. */
    private fun problems(text: String, sql: Boolean = looksLikeSql.containsMatchIn(text)): List<Pair<String, Int>> {
        val rules = if (sql) syntaxRules + sqlOnlyRules else syntaxRules
        return rules.flatMap { r -> r.regex.findAll(text).map { r.name to it.range.first }.toList() } +
            (if (sql) updateFrom(text).map { "UPDATE ... FROM (3.33)" to it } else emptyList())
    }

    // ---- finding the statements in a source file

    /** A character literal such as `'"'` is blanked first, so its quote can never be paired with a string's quote. */
    private val charLiteral = Regex("'(?:[^'\\\\\\n]|\\\\.)'")
    private val stringLiteral = Regex("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)
    private val plus = Regex("\\s*\\+\\s*")

    private class Statement(val text: String, val line: Int)

    /** The string literals of a source file, with those joined by `+` read as one statement, and the line each began on. */
    private fun statements(source: String): List<Statement> {
        val clean = charLiteral.replace(source) { " ".repeat(it.value.length) }
        val out = mutableListOf<Statement>()
        var text = StringBuilder()
        var line = 0
        var previousEnd = -1
        for (m in stringLiteral.findAll(clean)) {
            val joined = previousEnd >= 0 && plus.matches(clean.substring(previousEnd, m.range.first))
            if (!joined) {
                if (text.isNotEmpty()) out += Statement(text.toString(), line)
                text = StringBuilder()
                line = clean.substring(0, m.range.first).count { it == '\n' } + 1
            }
            text.append(m.groups[1]?.value ?: m.groups[2]!!.value).append(' ')
            previousEnd = m.range.last + 1
        }
        if (text.isNotEmpty()) out += Statement(text.toString(), line)
        return out
    }

    private fun scanSources(roots: List<File>): List<String> =
        roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.flatMap { file ->
                statements(file.readText()).flatMap { st -> problems(st.text).map { (rule, _) -> "${file.path}:${st.line} uses $rule" } }
            }.toList()
        }

    // ---- the detector's own tests: one positive and one negative example per rule

    @Test fun theDetectorFlagsEachFeatureAndLeavesPlainSqlAlone() {
        val bad = listOf(
            "INSERT INTO t (a) VALUES (1) ON CONFLICT (a) DO UPDATE SET a = 2",
            "INSERT INTO t (a) VALUES (1) ON CONFLICT DO NOTHING",
            "DELETE FROM t RETURNING a",
            "SELECT row_number() OVER (ORDER BY a) FROM t",
            "SELECT row_number() OVER w FROM t WINDOW w AS (ORDER BY a)",
            "SELECT sum(a) OVER (PARTITION BY b) FROM t",
            "ALTER TABLE t RENAME COLUMN a TO b",
            "ALTER TABLE t DROP COLUMN a",
            "VACUUM INTO 'x.db'",
            "CREATE TABLE t (a INTEGER GENERATED ALWAYS AS (1))",
            "CREATE TABLE t (a INTEGER AS (b + 1) STORED)",
            "CREATE TABLE t (a INTEGER AS (b + 1) VIRTUAL)",
            "CREATE TABLE t (a INTEGER) STRICT",
            "CREATE TABLE t (a INTEGER), STRICT",
            "SELECT j -> '\$.a' FROM t",
            "SELECT IIF(a, 1, 2) FROM t",
            "WITH c AS MATERIALIZED (SELECT 1) SELECT * FROM c",
            "UPDATE t SET a = u.b FROM u WHERE u.id = t.id",
            "UPDATE t SET a = 1 FROM u",
            "SELECT * FROM t ORDER BY a NULLS LAST",
            "SELECT * FROM a RIGHT JOIN b ON 1",
            "SELECT * FROM t WHERE a = TRUE",
            "SELECT * FROM t WHERE a = false",
            "SELECT * FROM t WHERE a IS NOT TRUE",
            "SELECT * FROM t WHERE a IS FALSE",
            "SELECT ceil(a) FROM t",
            "SELECT floor(a) FROM t",
            "SELECT pow(a, 2) FROM t",
            "SELECT power(a, 2) FROM t",
            "SELECT sqrt(a) FROM t",
            "SELECT ln(a) FROM t",
            "SELECT log(a) FROM t",
            "SELECT log2(a) FROM t",
            "SELECT log10(a) FROM t",
            "SELECT exp(a) FROM t",
            "SELECT trunc(a) FROM t",
            "SELECT sign(a) FROM t",
            "SELECT mod(a, 2) FROM t",
            "SELECT pi() FROM t",
            "SELECT unixepoch('now')",
            "SELECT format('%d', a) FROM t",
            "SELECT unhex(a) FROM t",
            "SELECT concat(a, b) FROM t",
            "SELECT concat_ws(',', a, b) FROM t",
            "SELECT string_agg(a, ',') FROM t",
            "SELECT octet_length(a) FROM t",
            "SELECT timediff(a, b) FROM t",
        )
        for (sql in bad) assertTrue("should be flagged: $sql", problems(sql).isNotEmpty())
        val fine = listOf(
            "INSERT OR IGNORE INTO t (a) VALUES (1)",
            "UPDATE t SET a = (SELECT b FROM u WHERE u.id = t.id) WHERE id = 1",
            "UPDATE t SET a = CASE WHEN b THEN 1 ELSE 2 END WHERE x = 1",
            "CREATE TABLE IF NOT EXISTS `t` (`a` INTEGER NOT NULL, PRIMARY KEY(`a`), FOREIGN KEY(`a`) REFERENCES `u`(`b`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "DELETE FROM items WHERE rowid IN (SELECT rowid FROM items ORDER BY a LIMIT 3)",
            "SELECT * FROM t WHERE flag = 1 AND other = 0", // 1 and 0, not TRUE and FALSE
            "SELECT * FROM t WHERE name = 'trueish' OR a IS NULL",
            "SELECT lower(a), max(a, b), abs(a), round(a), length(a), instr(a, b), substr(a, 1) FROM t",
            "SELECT COALESCE(a, 0), CASE WHEN a THEN 1 ELSE 2 END, group_concat(a) FROM t GROUP BY b",
            "SELECT * FROM windows w JOIN overlaps o ON o.id = w.id", // words that merely start like OVER / WINDOW
        )
        for (sql in fine) assertEquals("should pass: $sql", emptyList<Pair<String, Int>>(), problems(sql))
        // and the literal finder sees SQL that is split across string pieces
        val split = statements("val q = \"INSERT INTO t VALUES (1) ON \" +\n \"CONFLICT (a) DO UPDATE SET a = 1\"")
        assertEquals(1, split.size)
        assertTrue(problems(split.single().text).isNotEmpty())
    }

    @Test fun functionAndKeywordRulesIgnoreStringsThatAreNotSql() {
        // Ordinary strings that contain the words must not be flagged...
        for (text in listOf("floor(x) of the building", "format(it) and log(this)", "it is TRUE that mod(3) holds", "concat(a) OVER nothing")) {
            val found = statements("val s = \"$text\"").flatMap { problems(it.text) }
            assertTrue("not SQL, so not flagged: $text -> $found", found.isEmpty())
        }
        // ...but the same words inside a statement are
        assertTrue(statements("val q = \"SELECT floor(a) FROM t\"").flatMap { problems(it.text) }.isNotEmpty())
        assertTrue(statements("val q = \"SELECT a FROM t WHERE b = \" +\n\"TRUE\"").flatMap { problems(it.text) }.isNotEmpty())
    }

    @Test fun aQuoteCharacterLiteralDoesNotThrowTheStringPairingOff() {
        val source = "val quote = '\"'\nval q = \"SELECT floor(a) FROM t\"\nval other = \"plain\""
        val found = statements(source)
        assertEquals(listOf("SELECT floor(a) FROM t ", "plain "), found.map { it.text })
        assertTrue(found.flatMap { problems(it.text) }.isNotEmpty())
        assertEquals(2, found.first().line)
    }

    // ---- the real code

    @Test fun noSourceFileUsesSqlNewerThanSqlite322() {
        val java = File("src/main/java")
        assertTrue("source root ${java.absolutePath} must exist (Gradle runs unit tests in the module directory)", java.isDirectory)
        val scanned = java.walkTopDown().count { it.isFile && it.extension == "kt" }
        assertTrue("only $scanned source files found: the scan would be vacuous", scanned > 20)
        val found = scanSources(listOf(java, File("src/main/kotlin")))
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
            sql.flatMap { s -> problems(s, sql = true).map { "${file.path}: ${it.first} in `${s.take(60)}`" } }
        }
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }
}
