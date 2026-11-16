package app.shoalwatch.data.db.sync

import androidx.room.TypeConverter
import app.shoalwatch.data.sync.ChangeKind

/**
 * [ChangeKind] is stored by `name`. A name this version does not know (a later version wrote it) reads as null: such a change
 * cannot be sent, so the outbox DAO reads `kind` as text and the store deletes that row with its marks (Task 3), because
 * reading an unknown one into the non-null entity column fails Room's not-null check.
 */
class SyncConverters {
    @TypeConverter fun fromChangeKind(value: ChangeKind): String = value.name
    @TypeConverter fun toChangeKind(name: String): ChangeKind? = ChangeKind.entries.firstOrNull { it.name == name }
}
