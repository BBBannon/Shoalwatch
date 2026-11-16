package app.shoalwatch.data.db

import app.shoalwatch.data.db.downloads.AutoNextRequestEntity
import app.shoalwatch.data.db.downloads.ProfileDownloadSettingsEntity
import app.shoalwatch.data.db.downloads.ReleasedEpisodeEntity
import app.shoalwatch.data.db.sync.OutboxEntity
import app.shoalwatch.data.db.sync.SyncConverters
import app.shoalwatch.data.db.sync.SyncMarkEntity
import app.shoalwatch.data.db.sync.SyncStateEntity
import app.shoalwatch.data.downloads.AfterFinish
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.sync.ChangeKind
import app.shoalwatch.testId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The v5 entities: `ChangeKind` stored by name (unknown reads as null, so the row can be dropped), and no `toString()` prints an id, a position or a time. */
class SyncEntitiesTest {
    private val c = SyncConverters()

    @Test fun changeKindsRoundTripByNameAndAnUnknownOneReadsAsNull() {
        ChangeKind.entries.forEach { assertEquals(it, c.toChangeKind(c.fromChangeKind(it))) }
        assertEquals("POSITION", c.fromChangeKind(ChangeKind.POSITION))
        assertNull(c.toChangeKind("RATING"))
    }

    @Test fun noNewEntityPrintsAnIdAPositionOrATime() {
        val server = testId("server")
        val user = testId("user")
        val item = testId("item")
        val series = testId("series")
        val texts = listOf(
            OutboxEntity(seq = 7, serverId = server, userId = user, itemId = item, itemKind = ItemKind.EPISODE, kind = ChangeKind.POSITION, flag = null,
                positionMs = 123_456, occurredAt = 987_654_321, refreshKeys = "resume", fromOfflinePlay = true),
            SyncMarkEntity(7, server, user, item, "resume"),
            SyncStateEntity(server, user, 987_654_321),
            RejectedSignInEntity(server, user, 987_654_321),
            ReleasedEpisodeEntity(server, user, item, series, testId("season"), 987_654_321),
            ProfileDownloadSettingsEntity(server, user, AfterFinish.ALWAYS, 3),
            AutoNextRequestEntity(server, user, series, item, DownloadQuality.HIGH, 987_654_321, 2, 987_654_322),
        ).map { it.toString() }
        for (text in texts) for (secret in listOf(server, user, item, series, "123456", "987654321")) assertFalse("$text shows $secret", secret in text)
        assertEquals("OutboxEntity(kind=POSITION)", texts.first())
    }
}
