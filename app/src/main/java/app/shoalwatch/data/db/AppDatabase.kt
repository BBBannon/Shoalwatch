package app.shoalwatch.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.shoalwatch.data.db.library.ItemDao
import app.shoalwatch.data.db.library.ItemEntity
import app.shoalwatch.data.db.library.ItemKindConverter
import app.shoalwatch.data.db.library.LibraryMaintenanceDao
import app.shoalwatch.data.db.library.ListDao
import app.shoalwatch.data.db.library.ListEntryEntity
import app.shoalwatch.data.db.library.ListStateEntity
import app.shoalwatch.data.db.library.UserDataEntity
import app.shoalwatch.data.db.library.ViewDao
import app.shoalwatch.data.db.library.ViewEntity

/**
 * Schema is exported to `app/schemas/` and checked in. There is deliberately no destructive
 * migration fallback: a missing migration must fail loudly rather than wipe a user's sign-ins.
 */
@Database(
    entities = [
        ServerEntity::class, AccountEntity::class, ActiveAccountEntity::class,
        ItemEntity::class, UserDataEntity::class, ViewEntity::class, ListEntryEntity::class, ListStateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(ItemKindConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun accountDao(): AccountDao
    abstract fun itemDao(): ItemDao
    abstract fun listDao(): ListDao
    abstract fun viewDao(): ViewDao
    abstract fun libraryMaintenanceDao(): LibraryMaintenanceDao

    companion object {
        const val FILE_NAME = "shoalwatch.db"

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, FILE_NAME)
                .addMigrations(Migration1To2)
                .build()
    }
}
