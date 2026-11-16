package app.shoalwatch.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.shoalwatch.data.db.downloads.Converters
import app.shoalwatch.data.db.downloads.DownloadDao
import app.shoalwatch.data.db.downloads.DownloadEntity
import app.shoalwatch.data.db.downloads.DownloadOwnerEntity
import app.shoalwatch.data.db.downloads.DownloadSegmentEntity
import app.shoalwatch.data.db.downloads.DownloadPermissionEntity
import app.shoalwatch.data.db.downloads.DownloadSettingsEntity
import app.shoalwatch.data.db.downloads.DownloadTrackEntity
import app.shoalwatch.data.db.downloads.OfflineLibraryDao
import app.shoalwatch.data.db.downloads.OfflinePlayDao
import app.shoalwatch.data.db.downloads.OfflinePlayEntity
import app.shoalwatch.data.db.downloads.PinnedArtworkEntity
import app.shoalwatch.data.db.downloads.AutoNextRequestEntity
import app.shoalwatch.data.db.downloads.ProfileDownloadSettingsEntity
import app.shoalwatch.data.db.downloads.ReleasedEpisodeEntity
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
import app.shoalwatch.data.db.library.VisibleItemEntity
import app.shoalwatch.data.db.playback.TrackChoiceDao
import app.shoalwatch.data.db.playback.TrackChoiceEntity
import app.shoalwatch.data.db.sync.OutboxDao
import app.shoalwatch.data.db.sync.OutboxEntity
import app.shoalwatch.data.db.sync.SyncConverters
import app.shoalwatch.data.db.sync.SyncMarkEntity
import app.shoalwatch.data.db.sync.SyncStateEntity

/**
 * Schema is exported to `app/schemas/` and checked in. There is deliberately no destructive
 * migration fallback: a missing migration must fail loudly rather than wipe a user's sign-ins.
 */
@Database(
    entities = [
        ServerEntity::class, AccountEntity::class, ActiveAccountEntity::class,
        ItemEntity::class, UserDataEntity::class, ViewEntity::class, ListEntryEntity::class, ListStateEntity::class, VisibleItemEntity::class,
        TrackChoiceEntity::class,
        DownloadEntity::class, DownloadOwnerEntity::class, DownloadTrackEntity::class, DownloadSegmentEntity::class, PinnedArtworkEntity::class,
        DownloadSettingsEntity::class, OfflinePlayEntity::class, DownloadPermissionEntity::class,
        OutboxEntity::class, SyncMarkEntity::class, SyncStateEntity::class, RejectedSignInEntity::class,
        ReleasedEpisodeEntity::class, ProfileDownloadSettingsEntity::class, AutoNextRequestEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(ItemKindConverter::class, Converters::class, SyncConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun accountDao(): AccountDao
    abstract fun itemDao(): ItemDao
    abstract fun listDao(): ListDao
    abstract fun viewDao(): ViewDao
    abstract fun libraryMaintenanceDao(): LibraryMaintenanceDao
    abstract fun trackChoiceDao(): TrackChoiceDao
    abstract fun downloadDao(): DownloadDao
    abstract fun offlineLibraryDao(): OfflineLibraryDao
    abstract fun offlinePlayDao(): OfflinePlayDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        const val FILE_NAME = "shoalwatch.db"

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, FILE_NAME)
                .addMigrations(Migration1To2, Migration2To3, Migration3To4, Migration4To5)
                .build()
    }
}
