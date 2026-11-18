package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural rules for the lock (Milestone 7): who may open it and who may remove a PIN without one; what screens may name; where
 * a PIN may live; the secure dialogs; the lock's clock; and the launch intent cleaned before anything is drawn. Source scans of
 * `src/main`; a comment line never counts, and failures name the file and line.
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

    @Test fun everyExpectedTripIsEndedWhenItsPromptAnswers() {
        val expecting = filesCalling(Regex("""\.expectReturn\("""))
        assertEquals(listOf("ui/KeyChainCertChooser.kt", "ui/nav/LockRoot.kt", "ui/nav/Routes.kt"), expecting)
        for (path in expecting) {
            val text = File(root, path).readText()
            val expects = Regex("""\.expectReturn\(""").findAll(text).count()
            val dones = Regex("""\.tripDone\(""").findAll(text).count()
            assertTrue("$path: every expectReturn() needs a tripDone() when the prompt answers", dones >= expects)
        }
    }

    /** Final review nit: the lock screen's own PIN field, "Use another profile" and "Forgot PIN?" ignore touches under another app's window, as the PIN dialogs do. */
    @Test fun theLockScreenIsBehindTheObscuredTouchFilter() {
        val text = File(root, "ui/lock/LockScreen.kt").readText().substringAfter("internal fun LockScreen(").substringBefore("state.choosing?.let")
        assertTrue("FilterObscuredTouches() in LockScreen", text.contains("FilterObscuredTouches()"))
        assertTrue("its hint is shown", text.contains("ObscuredTouchHint("))
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

    /** Review I1 of Tasks 7-8: the lock settings' one write without the household check is `LockGate`'s rule 5, wired in `LockParts`. */
    @Test fun onlyLockPartsTurnsTheAppLockOffWithoutTheHouseholdCheck() {
        assertEquals(listOf("LockParts.kt"), filesCalling(Regex("""\.turnOffAppLock\(""")))
    }

    /**
     * Security review M8 F2: connection settings are a household setting from every way in (the Servers list and each
     * certificate "Review" button). The check lives in the screen's ViewModel, so the one place that builds it must hand it the
     * real household guard; nothing else may build one.
     */
    @Test fun connectionSettingsAlwaysGetTheHouseholdCheck() {
        assertEquals(listOf("ui/nav/Routes.kt"), filesCalling(Regex("""(?<!class )(?<![\w.])ServerEditViewModel\(""")))
        val route = File(root, "ui/nav/Routes.kt").readText().replace("\r\n", "\n").substringAfter("fun ServerEditRoute(").substringBefore("\n}\n")
        assertTrue("built with the household guard", Regex("""ServerEditViewModel\([^)]*household\s*=\s*graph\.lockParts\.householdGuard""").containsMatchIn(route))
        assertTrue("the grown-up dialog is shown", route.contains("GrownUpDialog("))
        assertTrue("a refused check leaves the screen", Regex("""Door\.Shut\)\s*nav\.popBackStack\(\)""").containsMatchIn(route))
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

    /** Every code line (comments dropped) of the files under [dirs], as "path:line: text". */
    private fun codeLines(vararg dirs: String): List<Pair<String, String>> = sources
        .map { it.relativeTo(root).invariantSeparatorsPath to it }
        .filter { (path, _) -> dirs.any { path.startsWith(it) } }
        .flatMap { (path, file) ->
            file.readLines().mapIndexedNotNull { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) null else "$path:${i + 1}" to line
            }
        }

    private fun offences(lines: List<Pair<String, String>>, regex: Regex) = lines.filter { regex.containsMatchIn(it.second) }.map { "${it.first}: ${it.second.trim()}" }

    @Test fun screensNeverNameTheStoreTheLockTablesTheHasherOrTheProvedSwitches() {
        val banned = Regex("""(ServerStore|ProfileDao|ProfileLockEntity|PinHasher|ProvedSwitches)""")
        assertEquals(emptyList<String>(), offences(codeLines("ui/"), banned))
    }

    /**
     * A PIN or password is a `CharArray` or a `TextFieldState`, never a `String`. The one named exception is the wire call that
     * puts the password into the JSON body the server requires (`JellyfinGateway.authenticateByName`, Milestone 1): the request
     * body is text, so a `String` exists there whatever the parameter type.
     */
    @Test fun noPinOrPasswordIsAString() {
        val wire = Regex("""fun authenticateByName\(""")
        val found = offences(codeLines("ui/", "data/", "security/"), Regex("""(pin|Pin|password)\s*:\s*String"""))
            .filterNot { it.startsWith("data/servers/JellyfinGateway") && wire.containsMatchIn(it) }
        assertEquals(emptyList<String>(), found)
    }

    @Test fun lockAndSettingsScreensKeepNoPinAnywhereElse() {
        val lines = codeLines("ui/lock/", "ui/settings/")
        val saveablePin = Regex("""(pin|Pin|password|Password)\w*\s*(by|=)\s*rememberSaveable""")
        assertEquals(emptyList<String>(), offences(lines, saveablePin))
        assertEquals(emptyList<String>(), offences(lines, Regex("""SavedStateHandle|ClipboardManager|LocalClipboard""")))
        assertEquals(emptyList<String>(), offences(lines, Regex("""(?<![A-Za-z])Log\.""")))
    }

    @Test fun everyDialogInTheLockAndSettingsScreensIsSecure() {
        val opens = Regex("""(?<![\w.])(AlertDialog|Dialog|BasicAlertDialog)\(""")
        val missing = sources.filter { f -> f.relativeTo(root).invariantSeparatorsPath.let { it.startsWith("ui/lock/") || it.startsWith("ui/settings/") } }
            .flatMap { f ->
                val lines = f.readLines()
                lines.indices.filter { i -> opens.containsMatchIn(lines[i]) && !lines[i].trim().startsWith("*") && !lines[i].trim().startsWith("//") }
                    .filterNot { i -> lines.subList(i, minOf(lines.size, i + 4)).any { it.contains("properties = SecureDialog") } }
                    .map { "${f.relativeTo(root).invariantSeparatorsPath}:${it + 1}: ${lines[it].trim()}" }
            }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun theLockIsTimedOnlyByTheMonotonicClock() {
        listOf("security/ProfileLocks.kt", "security/LockGate.kt").forEach { path ->
            val found = offences(codeLines(path), Regex("""System\.currentTimeMillis|Instant\.now"""))
            assertEquals(path, emptyList<String>(), found)
        }
    }

    /**
     * Review M5 of Tasks 9-10: `MainActivity` hands its own start and stop to `ActivityPip`'s rule (so a start in the small window
     * is not a return: review C1) and hands it the lock gate itself, so nothing in the activity can swap leaving and returning.
     */
    @Test fun theActivityTellsTheLockOnlyThroughThePictureInPictureRule() {
        val text = File(root, "ui/MainActivity.kt").readText()
        val onStart = text.substringAfter("override fun onStart(").substringBefore("override fun ")
        val onStop = text.substringAfter("override fun onStop(").substringBefore("override fun ")
        assertTrue("onStart asks the rule", onStart.contains("pip.onActivityStarted()"))
        assertTrue("onStop asks the rule", onStop.contains("pip.onActivityStopped()"))
        assertTrue("never straight to the gate", !onStart.contains("lockGate") && !onStop.contains("lockGate"))
        assertTrue("the gate itself", text.contains("trips = { ShoalwatchApp.graph(this).lockParts.lockGate }"))
    }

    @Test fun theLaunchIntentIsCleanedBeforeAnythingIsDrawn() {
        val onCreate = File(root, "ui/MainActivity.kt").readText().substringAfter("override fun onCreate(").substringBefore("override fun ")
        val clean = onCreate.indexOf("sanitised(")
        val draw = onCreate.indexOf("setContent")
        assertTrue("MainActivity.onCreate cleans the intent", clean >= 0)
        assertTrue("setContent is in onCreate", draw >= 0)
        assertTrue("sanitised( comes before setContent", clean < draw)
    }
}
