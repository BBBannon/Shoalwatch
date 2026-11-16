package app.shoalwatch.data.sync

import app.shoalwatch.data.db.sync.OutboxEntity
import app.shoalwatch.data.library.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every row of the conflict table, the margin on both sides, and why a retry can never count a play twice. */
class ConflictRuleTest {
    private val t = 1_800_000_000_000L

    private fun row(kind: ChangeKind, itemKind: ItemKind = ItemKind.EPISODE, flag: Boolean? = null, positionMs: Long? = null,
                    sentOnce: Boolean = false, sentPlayCount: Int? = null) = OutboxEntity(
        seq = 1, serverId = "s", userId = "u", itemId = "i", itemKind = itemKind, kind = kind, flag = flag, positionMs = positionMs,
        occurredAt = t, refreshKeys = "", fromOfflinePlay = false, sentOnce = sentOnce, sentPlayCount = sentPlayCount,
    )

    private fun server(played: Boolean = false, count: Int? = 0, lastPlayedAt: Long? = null) = ServerUserData(played, count, 0, false, lastPlayedAt)
    private val newer = t - 119_000 // inside the margin: the server wins
    private val older = t - 121_000

    private fun decide(row: OutboxEntity, server: ServerUserData?) = ConflictRule.decide(row, server)
    private fun update(played: Boolean? = null, playCount: Int? = null, positionMs: Long? = null, lastPlayedAt: Long? = null) =
        Step.Update(UserDataUpdate(played, playCount, positionMs, lastPlayedAt))

    @Test fun playedTrueOnAFilmOrEpisode() {
        for (kind in listOf(ItemKind.MOVIE, ItemKind.EPISODE)) {
            val r = row(ChangeKind.PLAYED, kind, flag = true)
            assertTrue(ConflictRule.needsServerCopy(r))
            assertEquals(Step.AlreadyThere, decide(r, server(played = true, count = 3)))
            assertEquals(update(played = true, playCount = 1, positionMs = 0), decide(r, server(count = 0)))
            assertEquals(update(played = true, playCount = 4, positionMs = 0), decide(r, server(count = 4)))
            assertEquals("an out-of-range count reads as 0", update(played = true, playCount = 1, positionMs = 0), decide(r, server(count = null)))
            assertEquals("unknown: no count", update(played = true, positionMs = 0), decide(r, null))
        }
    }

    @Test fun playedTrueOnAContainerIsMarkPlayedWithoutReading() {
        for (kind in listOf(ItemKind.SEASON, ItemKind.SERIES, ItemKind.BOX_SET, ItemKind.FOLDER, ItemKind.OTHER)) {
            val r = row(ChangeKind.PLAYED, kind, flag = true)
            assertFalse(ConflictRule.needsServerCopy(r))
            assertEquals(Step.MarkPlayed, decide(r, null))
        }
    }

    @Test fun playedFalseNeverUndoesANewerWatch() {
        for (kind in listOf(ItemKind.EPISODE, ItemKind.SEASON)) {
            val r = row(ChangeKind.PLAYED, kind, flag = false)
            assertTrue(ConflictRule.needsServerCopy(r))
            assertEquals(Step.AlreadyThere, decide(r, server(played = false)))
            assertEquals(Step.ServerIsNewer, decide(r, server(played = true, lastPlayedAt = newer)))
            assertEquals(Step.MarkUnplayed, decide(r, server(played = true, lastPlayedAt = older)))
            assertEquals("no date on the server: older", Step.MarkUnplayed, decide(r, server(played = true, lastPlayedAt = null)))
            assertEquals(Step.MarkUnplayed, decide(r, null))
        }
    }

    @Test fun favouriteIsAnExplicitTapAndReadsNothing() {
        val on = row(ChangeKind.FAVOURITE, flag = true)
        assertFalse(ConflictRule.needsServerCopy(on))
        assertEquals(Step.Favourite(true), decide(on, null))
        assertEquals(Step.Favourite(false), decide(row(ChangeKind.FAVOURITE, flag = false), server(lastPlayedAt = newer)))
    }

    @Test fun positionLosesToANewerChangeAndZeroNeverSendsADate() {
        val p = row(ChangeKind.POSITION, positionMs = 42_000)
        assertTrue(ConflictRule.needsServerCopy(p))
        assertEquals(Step.ServerIsNewer, decide(p, server(played = true, lastPlayedAt = newer)))
        assertEquals(Step.ServerIsNewer, decide(p, server(lastPlayedAt = newer)))
        assertEquals(update(positionMs = 42_000, lastPlayedAt = t), decide(p, server(lastPlayedAt = older)))
        assertEquals(update(positionMs = 42_000, lastPlayedAt = t), decide(p, null))
        val zero = row(ChangeKind.POSITION, positionMs = 0)
        assertEquals(update(positionMs = 0), decide(zero, server(lastPlayedAt = older)))
        assertEquals(update(positionMs = 0), decide(zero, null))
    }

    @Test fun theMarginOnBothSides() {
        val p = row(ChangeKind.POSITION, positionMs = 1)
        assertEquals(Step.ServerIsNewer, decide(p, server(lastPlayedAt = t - 119_000)))
        assertEquals(Step.ServerIsNewer, decide(p, server(lastPlayedAt = t - ConflictRule.MARGIN_MS)))
        assertEquals(update(positionMs = 1, lastPlayedAt = t), decide(p, server(lastPlayedAt = t - 121_000)))
        assertEquals(Step.ServerIsNewer, decide(p, server(lastPlayedAt = t + 3_600_000)))
    }

    @Test fun aFirstPlayCountsOneMoreThanTheServerWithItsDate() {
        val r = row(ChangeKind.PLAY)
        assertTrue(ConflictRule.needsServerCopy(r))
        assertEquals(update(played = true, playCount = 3, positionMs = 0, lastPlayedAt = t), decide(r, server(count = 2, lastPlayedAt = older)))
        assertEquals("newer on the server: count only", update(played = true, playCount = 3), decide(r, server(played = true, count = 2, lastPlayedAt = newer)))
    }

    @Test fun aPlayWhoseFirstSendLandedIsAlreadyThere() {
        val r = row(ChangeKind.PLAY, sentOnce = true, sentPlayCount = 3)
        assertEquals("count equal", Step.AlreadyThere, decide(r, server(played = true, count = 3, lastPlayedAt = t)))
        assertEquals("count higher", Step.AlreadyThere, decide(r, server(played = true, count = 5, lastPlayedAt = t)))
    }

    @Test fun aPlayRetriedAfterALostReplyResendsTheStoredCountNotServerPlusOne() {
        val r = row(ChangeKind.PLAY, sentOnce = true, sentPlayCount = 3)
        assertEquals(update(played = true, playCount = 3, positionMs = 0, lastPlayedAt = t), decide(r, server(count = 2, lastPlayedAt = older)))
        assertEquals(update(played = true, playCount = 3, positionMs = 0, lastPlayedAt = t), decide(r, server(count = null, lastPlayedAt = older)))
    }

    @Test fun unknownCopiesNeverProduceAPlayCountForPlay() {
        val step = decide(row(ChangeKind.PLAY), null) as Step.Update
        assertNull(step.body.playCount)
        assertEquals(update(played = true, positionMs = 0, lastPlayedAt = t), step)
        assertNull((decide(row(ChangeKind.PLAY), server(count = null, lastPlayedAt = older)) as Step.Update).body.playCount)
    }
}
