package app.shoalwatch.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Schema is exported to `app/schemas/` and checked in. There is deliberately no destructive
 * migration fallback: a missing migration must fail loudly rather than wipe a user's sign-ins.
 */
@Database(
    entities = [ServerEntity::class, AccountEntity::class, ActiveAccountEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun accountDao(): AccountDao

    companion object {
        const val FILE_NAME = "shoalwatch.db"

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, FILE_NAME).build()
    }
}
