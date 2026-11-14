package app.shoalwatch.data.db

import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.library.LibraryKind
import app.shoalwatch.data.library.LibraryView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Removing an account or a server, through the real [app.shoalwatch.data.servers.ServerStore], cascades correctly. */
class LibraryCascadeTest : LibraryTestBase() {
    private val tables = listOf("items", "user_data", "views", "list_entries", "list_state")

    private fun fill() = runBlocking {
        for (s in listOf(s1u1, s1u2, s2u1)) {
            library.writePage(s, "resume", 0, listOf(row(item("a"), watched(played = true)), row(item("b"), watched(favourite = true))), 2, t0)
            library.replaceViews(s, listOf(LibraryView(id("v"), "Films", LibraryKind.MOVIES)))
        }
    }

    private fun counts(where: String) = tables.associateWith { count(it, where) }

    @Test fun removingAnAccountDeletesThatUsersRowsAndLeavesTheItems() = runBlocking {
        fill()
        val itemsBefore = count("items")
        servers.removeAccount(S1, U1)
        for (table in tables - "items") assertEquals("$table for the removed user", 0, count(table, "serverId = '$S1' AND userId = '$U1'"))
        assertEquals("items are shared, so they stay", itemsBefore, count("items"))
        val other = (tables - "items").associateWith { count(it, "serverId = '$S1' AND userId = '$U2'") }
        assertEquals(mapOf("user_data" to 2, "views" to 1, "list_entries" to 2, "list_state" to 1), other)
        assertEquals(2, count("user_data", "serverId = '$S2'"))
    }

    @Test fun removingAServerDeletesEverythingForItAndNothingElse() = runBlocking {
        fill()
        servers.removeServer(S1)
        assertEquals(tables.associateWith { 0 }, counts("serverId = '$S1'"))
        assertEquals(2, count("items", "serverId = '$S2'"))
        assertEquals(2, count("user_data", "serverId = '$S2' AND userId = '$U1'"))
        assertEquals(2, count("list_entries", "serverId = '$S2'"))
        assertEquals(1, count("list_state", "serverId = '$S2'"))
        assertEquals(1, count("views", "serverId = '$S2'"))
    }

    @Test fun removingAServerThatHasNoLibraryRowsStillWorks() = runBlocking {
        servers.removeServer(S2)
        assertEquals(0, count("accounts", "serverId = '$S2'"))
    }
}
