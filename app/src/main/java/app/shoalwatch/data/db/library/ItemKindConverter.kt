package app.shoalwatch.data.db.library

import androidx.room.TypeConverter
import app.shoalwatch.data.library.ItemKind

/** Kinds are stored by name; a name this version does not know (a later version wrote it) reads as [ItemKind.OTHER]. */
class ItemKindConverter {
    @TypeConverter fun toName(kind: ItemKind): String = kind.name

    @TypeConverter fun fromName(name: String): ItemKind = ItemKind.entries.firstOrNull { it.name == name } ?: ItemKind.OTHER
}
