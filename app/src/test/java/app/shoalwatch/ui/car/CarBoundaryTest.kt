package app.shoalwatch.ui.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Milestone 9 source rules for the Android Auto car screen: Android Auto blanks a secure window on the car display, so nothing in
 * `ui/car` makes one; the exported activity reads nothing from its intent and has no Navigation (deep links); and its trips reach
 * the lock only through the car's side of `AppFront`.
 */
class CarBoundaryTest {
    private val root = File("src/main/java/app/shoalwatch")
    private val car = File(root, "ui/car").walkTopDown().filter { it.extension == "kt" }.toList()

    /** The file without its comments (they may name what the code must never do). */
    private fun code(f: File) = f.readLines().filterNot { val l = it.trimStart(); l.startsWith("*") || l.startsWith("//") || l.startsWith("/*") }.joinToString("\n")

    @Test fun nothingOnTheCarScreenMakesASecureWindow() {
        assertTrue(car.size >= 3)
        val banned = listOf("FLAG_SECURE", "SecureWindow", "SecureDialog", "RecentsPrivacy", "setRecentsScreenshotEnabled", "ui.lock")
        val found = car.flatMap { f -> banned.filter { code(f).contains(it) }.map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), found)
    }

    @Test fun theCarActivityReadsNothingFromItsIntentAndHasNoNavigation() {
        val code = code(File(root, "ui/car/ParkedCarActivity.kt"))
        assertTrue("no intent read", !Regex("""\bintent\b|getIntent|onNewIntent""").containsMatchIn(code))
        assertTrue("no Navigation", car.none { it.readText().contains("androidx.navigation") })
    }

    @Test fun theCarTellsTheLockOnlyThroughItsSideOfAppFront() {
        val text = File(root, "ui/car/ParkedCarActivity.kt").readText()
        val onResume = text.substringAfter("override fun onResume(").substringBefore("override fun ")
        val onPause = text.substringAfter("override fun onPause(").substringBefore("companion object")
        assertTrue(onResume.contains("graph.lockParts.carTrips.onReturned()"))
        assertTrue(onPause.contains("graph.lockParts.carTrips.onLeft()"))
        assertTrue("never the gate's own trips", !Regex("""lockGate\.on(Left|Returned)""").containsMatchIn(text))
        val users = root.walkTopDown().filter { it.extension == "kt" && it.readText().contains("carTrips") }.map { it.name }.toSet()
        assertEquals(setOf("LockParts.kt", "ParkedCarActivity.kt"), users)
    }
}
