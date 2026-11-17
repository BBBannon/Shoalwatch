package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural rules for the lock (Milestone 7): who may open it and who may remove a PIN without one. Source scans of `src/main`.
 */
class LockBoundaryTest {
    private val root = File("src/main/java/app/shoalwatch")
    private val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun filesCalling(regex: Regex): List<String> =
        sources.filter { regex.containsMatchIn(it.readText()) }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted()

    @Test fun onlyTheScreenLockPromptsSuccessOpensTheAppLock() {
        assertEquals(listOf("ui/nav/LockRoot.kt"), filesCalling(Regex("""\.appUnlocked\(""")))
        val text = File(root, "ui/nav/LockRoot.kt").readText()
        val calls = Regex("""\.appUnlocked\(""").findAll(text).toList()
        assertEquals("one call", 1, calls.size)
        val line = text.lineSequence().first { it.contains(".appUnlocked(") }
        assertTrue("only on SUCCESS: $line", line.contains("AuthOutcome.SUCCESS"))
    }

    @Test fun aPinIsClearedOnlyWithTheProofOfAPasswordSignIn() {
        assertEquals(listOf("LockParts.kt"), filesCalling(Regex("""\.clear\(\s*it\s*\)|[Ll]ocks\.clear\(""")))
        assertEquals("the proof is made only by the lock screen", listOf("ui/lock/LockViewModel.kt"), filesCalling(Regex("""ClearProof\.afterPasswordSignIn\(""")))
        val vm = File(root, "ui/lock/LockViewModel.kt").readText()
        val signIn = vm.indexOf("val account = authenticator.signInWithPassword(")
        val proof = vm.indexOf("ClearProof.afterPasswordSignIn(locked.session, account)")
        assertTrue("the proof is made from the server's answer to the password", signIn in 0 until proof)
        assertTrue("ClearProof's constructor is private", File(root, "security/ClearProof.kt").readText().contains("class ClearProof private constructor"))
    }

    /**
     * Tasks 5-6 review M3: a [app.shoalwatch.security.ClearProof] is made in one place only, the Forgot PIN path, from the server's
     * answer to the password. Nothing else in the app builds one (its constructor is private and its file has one factory), and the
     * factory is called exactly once, so a new caller that builds an `AccountSummary` by hand fails here.
     */
    @Test fun aClearProofIsMadeOnlyOnTheForgotPinPath() {
        val constructorCall = Regex("""(?<![\w."])ClearProof\(""")
        val proofFile = File(root, "security/ClearProof.kt").readText()
        assertTrue("ClearProof's constructor is private", proofFile.contains("class ClearProof private constructor"))
        assertEquals("one factory", 1, Regex("""\bfun """).findAll(proofFile.substringAfter("companion object")).count())
        assertEquals("built only by that factory", 1, constructorCall.findAll(proofFile).count())
        val outside = sources.filter { it.name != "ClearProof.kt" }
        assertTrue("no other file calls its constructor", outside.none { constructorCall.containsMatchIn(it.readText()) })
        val calls = outside.sumOf { Regex("""ClearProof\.afterPasswordSignIn\(""").findAll(it.readText()).count() }
        assertEquals("the factory is called once, on the Forgot PIN path", 1, calls)
        val forgot = File(root, "ui/lock/LockViewModel.kt").readText().substringAfter("fun submitForgot()").substringBefore("private fun attempt(")
        assertTrue("inside submitForgot", forgot.contains("ClearProof.afterPasswordSignIn(locked.session, account)"))
    }

    @Test fun noScreenChangesTheActiveProfileButThroughTheSwitcher() {
        assertEquals("ui/ calls setActive", emptyList<String>(), filesCalling(Regex("""\.setActive\(""")).filter { it.startsWith("ui/") })
    }

    @Test fun theLockScreenOffersNoQuickConnect() {
        listOf("ui/lock/LockViewModel.kt", "ui/lock/ForgotPinPanel.kt", "ui/lock/LockScreen.kt").forEach {
            val code = File(root, it).readLines().filterNot { l -> l.trim().startsWith("*") || l.trim().startsWith("/") }.joinToString("\n")
            assertTrue("$it uses Quick Connect", !code.contains("quickConnect"))
        }
    }
}
