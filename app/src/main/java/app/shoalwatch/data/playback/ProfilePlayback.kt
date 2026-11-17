package app.shoalwatch.data.playback

import app.shoalwatch.data.db.profile.ProfileDao
import app.shoalwatch.data.db.profile.ProfilePlaybackEntity
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.retryingReads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Each profile's own playback settings (Milestone 7): "Play the next episode automatically", in Room's `profile_playback` (gone
 * with the account). No row means on, as before this setting existed.
 */
class ProfilePlayback(private val dao: ProfileDao) {
    /** Default true (as before this setting existed). */
    fun autoplay(s: Session): Flow<Boolean> = dao.observeAutoplay(s.serverId, s.userId).map { it ?: true }.distinctUntilChanged()

    suspend fun setAutoplay(s: Session, on: Boolean) = dao.upsertPlayback(ProfilePlaybackEntity(s.serverId, s.userId, on))

    /**
     * For `PlaybackController`'s `autoplayNext`: reads the latest value, kept hot in [scope] (the player screen's). On until the
     * first read; a failed read keeps the last value and reads again.
     */
    fun autoplayReader(s: Session, scope: CoroutineScope): () -> Boolean {
        val latest = autoplay(s).retryingReads().stateIn(scope, SharingStarted.Eagerly, true)
        return { latest.value }
    }

    override fun toString() = "ProfilePlayback(<redacted>)"
}
