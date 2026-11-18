package app.shoalwatch.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Security review M8 F9: the app's database overwrites what it deletes, so a signed-out profile's rows are not left readable in
 * the file. Android's own SQLite is built with `SQLITE_SECURE_DELETE`, so this is on without any pragma from the app (the test
 * passed before any change was made); it guards against that ever changing.
 */
@RunWith(RobolectricTestRunner::class)
class SecureDeleteTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = AppDatabase.open(context)

    @After fun close() {
        db.close()
        context.deleteDatabase(AppDatabase.FILE_NAME)
    }

    @Test fun theDatabaseOpensWithSecureDeleteOn() {
        val value = db.openHelper.writableDatabase.query("PRAGMA secure_delete").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, value)
    }
}
