package app.shoalwatch.data.db.downloads

import androidx.room.TypeConverter
import app.shoalwatch.data.downloads.AfterFinish
import app.shoalwatch.data.downloads.ArtworkSlot
import app.shoalwatch.data.downloads.DownloadFailure
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.SharedDelete
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.downloads.TrackType
import app.shoalwatch.data.playback.SegmentKind
import app.shoalwatch.data.playback.SubtitleKind

/**
 * The download tables' enums, stored by `name`. A name this version does not know (a later version wrote it) reads as a
 * safe value: a state as FAILED (never something that would run), a failure as UNKNOWN, a quality as ORIGINAL, storage as
 * INTERNAL, "after finish" as ASK and "delete shared downloads" as ANYONE. The kinds of an extra ([ArtworkSlot], [TrackType], [SubtitleKind], [SegmentKind]) read as null instead, and the
 * DAO mapper drops that row: reading an unknown one into a non-null entity column fails Room's not-null check, so DAOs read
 * those tables into rows with the column as a String and map through these functions (Tasks 3 and 8).
 */
class Converters {
    @TypeConverter fun fromQuality(value: DownloadQuality): String = value.name
    @TypeConverter fun toQuality(name: String): DownloadQuality = known<DownloadQuality>(name) ?: DownloadQuality.ORIGINAL

    @TypeConverter fun fromState(value: DownloadState): String = value.name
    @TypeConverter fun toState(name: String): DownloadState = known<DownloadState>(name) ?: DownloadState.FAILED

    @TypeConverter fun fromFailure(value: DownloadFailure?): String? = value?.name
    @TypeConverter fun toFailure(name: String?): DownloadFailure? = name?.let { known<DownloadFailure>(it) ?: DownloadFailure.UNKNOWN }

    @TypeConverter fun fromStorage(value: StorageKind): String = value.name
    @TypeConverter fun toStorage(name: String): StorageKind = known<StorageKind>(name) ?: StorageKind.INTERNAL

    @TypeConverter fun fromAfterFinish(value: AfterFinish): String = value.name
    @TypeConverter fun toAfterFinish(name: String): AfterFinish = known<AfterFinish>(name) ?: AfterFinish.ASK

    @TypeConverter fun fromSharedDelete(value: SharedDelete): String = value.name
    @TypeConverter fun toSharedDelete(name: String): SharedDelete = known<SharedDelete>(name) ?: SharedDelete.ANYONE

    @TypeConverter fun fromSlot(value: ArtworkSlot): String = value.name
    @TypeConverter fun toSlot(name: String): ArtworkSlot? = known<ArtworkSlot>(name)

    @TypeConverter fun fromTrackType(value: TrackType): String = value.name
    @TypeConverter fun toTrackType(name: String): TrackType? = known<TrackType>(name)

    @TypeConverter fun fromSubtitleKind(value: SubtitleKind?): String? = value?.name
    @TypeConverter fun toSubtitleKind(name: String?): SubtitleKind? = name?.let { known<SubtitleKind>(it) }

    @TypeConverter fun fromSegmentKind(value: SegmentKind): String = value.name
    @TypeConverter fun toSegmentKind(name: String): SegmentKind? = known<SegmentKind>(name)

    private inline fun <reified E : Enum<E>> known(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
}
