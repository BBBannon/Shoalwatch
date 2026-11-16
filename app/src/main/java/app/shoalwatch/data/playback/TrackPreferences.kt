package app.shoalwatch.data.playback

import app.shoalwatch.data.db.playback.TrackChoiceDao
import app.shoalwatch.data.db.playback.TrackChoiceEntity
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException

/** A remembered choice, by identity: languages and flags (research complaint 11), never a stream index. */
data class TrackChoice(
    val audioLanguage: String?,
    val subtitleLanguage: String?,
    val subtitlesOff: Boolean,
    val forced: Boolean,
    val hearingImpaired: Boolean,
)

/**
 * The audio and subtitle choice per (server, user, series), in Room (`track_choices`, deleted with the account). Only the
 * session's own row is ever read or written, so a profile never sees or inherits another's. Films (no series) are not
 * remembered. Best effort: a failed read is no memory, and a failed write (the account went meanwhile) is dropped, so
 * neither can stop playback.
 */
class TrackPreferences(private val dao: TrackChoiceDao, private val clock: () -> Long) {
    suspend fun get(s: Session, seriesId: String?): TrackChoice? {
        val series = seriesId?.takeIf(::isId) ?: return null
        val row = try {
            dao.get(s.serverId, s.userId, series)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return TrackChoice(row.audioLanguage, row.subtitleLanguage, row.subtitlesOff, row.subtitleForced, row.subtitleHearingImpaired)
    }

    suspend fun remember(s: Session, seriesId: String?, choice: TrackChoice) {
        val series = seriesId?.takeIf(::isId) ?: return
        val row = TrackChoiceEntity(
            s.serverId, s.userId, series, choice.audioLanguage?.take(MAX_LANGUAGE), choice.subtitleLanguage?.take(MAX_LANGUAGE),
            choice.subtitlesOff, choice.forced, choice.hearingImpaired, clock(),
        )
        try {
            dao.upsert(row)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Unit // the account was removed meanwhile (foreign key) or the database failed: nothing to remember
        }
    }

    private fun isId(id: String) = JellyfinId.parseOrNull(id)?.value == id

    private companion object {
        const val MAX_LANGUAGE = 16
    }
}
