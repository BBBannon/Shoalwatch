package app.shoalwatch.data.db.playback

import androidx.room.Entity
import androidx.room.ForeignKey
import app.shoalwatch.data.db.AccountEntity

/**
 * The audio and subtitle choice one user made for one series, by identity (language and flags, never a stream index: the
 * next episode's indices differ). Keyed by the account, so no other profile reads it; deleted with the account.
 */
@Entity(
    tableName = "track_choices",
    primaryKeys = ["serverId", "userId", "seriesId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["serverId", "userId"],
            childColumns = ["serverId", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TrackChoiceEntity(
    val serverId: String,
    val userId: String,
    val seriesId: String,
    val audioLanguage: String?,
    val subtitleLanguage: String?,
    val subtitlesOff: Boolean,
    val subtitleForced: Boolean,
    val subtitleHearingImpaired: Boolean,
    val updatedAt: Long,
)
