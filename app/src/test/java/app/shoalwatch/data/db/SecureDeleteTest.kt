package app.shoalwatch.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Security review M8 F9 (review M-6): the app's database overwrites what it deletes, so a signed-out profile's rows are not left
 * readable in the file. `AppDatabase.open` sets `PRAGMA secure_delete = ON` on every open. Under Robolectric the pragma is answered
 * by the host's SQLite, which already defaults it on, so this test passed before the pragma too: it checks the setting is on, not
 * a device build's default. WAL frames written before a delete can hold old rows until the next checkpoint (accepted).
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
