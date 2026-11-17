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

    @Test fun theLockScreenOffersNoQuickConnect() {
        listOf("ui/lock/LockViewModel.kt", "ui/lock/ForgotPinPanel.kt", "ui/lock/LockScreen.kt").forEach {
            val code = File(root, it).readLines().filterNot { l -> l.trim().startsWith("*") || l.trim().startsWith("/") }.joinToString("\n")
            assertTrue("$it uses Quick Connect", !code.contains("quickConnect"))
        }
    }
}
