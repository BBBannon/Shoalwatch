package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.downloads.DownloadDao
import app.shoalwatch.data.db.library.ItemDao
import app.shoalwatch.data.db.library.toModel
import app.shoalwatch.data.downloads.api.DownloadFacts
import app.shoalwatch.data.downloads.api.DownloadGateway
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.playback.api.PlaybackInfo
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * What one press will download, how big it is, and whether it will play (see "Download planner" in docs/ARCHITECTURE.md).
 * Online only: the server's answers decide, and OFFLINE or any other AppError from them is thrown as it comes. Metadata is
 * written only through [Library.refresh], so every item planned has the session's visibility record; nothing is queued.
 * [checkOriginal] is `PlaybackResolver::checkOriginal`.
 */
internal class DownloadPlanner(
    private val library: Library,
    private val items: ItemDao,
    private val gateway: DownloadGateway,
    private val checkOriginal: suspend (Session, String) -> PlaybackInfo,
    private val downloads: DownloadDao,
    private val volumes: Volumes,
    private val prefs: DownloadPrefs,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxItems: Int = 500,
    private val concurrency: Int = 4,
) {
    private sealed interface Outcome {
        data class Planned(val item: PlannedItem, val converted: Boolean = false) : Outcome
        data class Skip(val skipped: Skipped) : Outcome
    }

    suspend fun plan(s: Session, target: DownloadTarget, quality: DownloadQuality): DownloadPlan = withContext(io) {
        val (candidates, fallbackId) = expand(s, target)
        val chosen = candidates.take(maxItems)
        val ids = chosen.map { it.id }.ifEmpty { listOf(fallbackId) } // nothing saved: still ask, so offline is OFFLINE
        val facts = ids.chunked(FACTS_CHUNK).flatMap { gateway.facts(s, it) }.associateBy { it.itemId }
        val here = chosen.associate { it.id to downloads.get(s.serverId, it.id)?.quality } // read before the checks fan out
        val gate = Semaphore(concurrency)
        val outcomes = coroutineScope {
            chosen.map { item -> async { decide(s, item, facts[item.id], here[item.id], quality, gate) } }.awaitAll()
        }
        val planned = outcomes.filterIsInstance<Outcome.Planned>()
        val skipped = outcomes.filterIsInstance<Outcome.Skip>().map { it.skipped } +
            if (candidates.isEmpty() && target is DownloadTarget.Item) listOf(Skipped(target.itemId, SkipReason.NOT_FOUND)) else emptyList()
        val settings = prefs.settings.value
        val total = planned.fold(0L) { sum, p -> saturatingAdd(sum, p.item.estimatedBytes) }
        val free = volumes.freeBytes(settings.storage, settings.volumeId)
        val reserve = if (settings.storage == StorageKind.INTERNAL) INTERNAL_RESERVE else REMOVABLE_RESERVE
        DownloadPlan(
            session = s, requested = quality, items = planned.map { it.item }, skipped = skipped,
            convertedInstead = planned.count { it.converted },
            totalBytes = total, freeBytes = free, enoughSpace = saturatingAdd(total, reserve) <= free,
            storage = settings.storage, volumeId = settings.volumeId, mobileData = settings.mobileData,
            capped = candidates.size > maxItems,
        )
    }

    /** The target's films or episodes as saved for [s] after refreshing them, in episode order; and an id to ask about if none. */
    private suspend fun expand(s: Session, target: DownloadTarget): Pair<List<Item>, String> = when (target) {
        is DownloadTarget.Item -> {
            library.refresh(s, RefreshTarget.Item(target.itemId))
            val item = visible(s, target.itemId)
            if (item?.kind == ItemKind.EPISODE && item.seriesId != null) library.refresh(s, RefreshTarget.Series(item.seriesId, item.seasonId))
            listOfNotNull(item?.takeIf { it.kind == ItemKind.MOVIE || it.kind == ItemKind.EPISODE }) to target.itemId
        }
        is DownloadTarget.Season -> {
            library.refresh(s, RefreshTarget.Series(target.seriesId, target.seasonId))
            episodes(s, target.seriesId).filter { it.item.seasonId == target.seasonId }.map { it.item } to target.seriesId
        }
        is DownloadTarget.Series -> numbered(s, target.seriesId).map { it.item } to target.seriesId
        // No user data yet means never played: unwatched.
        is DownloadTarget.Unwatched -> numbered(s, target.seriesId).filter { it.userData?.played != true }.map { it.item } to target.seriesId
    }

    /** Every numbered season's episodes, plus episodes with no season; Specials (season 0, or episode season number 0) left out. */
    private suspend fun numbered(s: Session, seriesId: String): List<ItemWithUserData> {
        library.refresh(s, RefreshTarget.Series(seriesId))
        val seasons = items.getSeasons(s.serverId, s.userId, seriesId).map { it.toModel().item }.filter { (it.indexNumber ?: 0) > 0 }
        for (season in seasons) library.refresh(s, RefreshTarget.Series(seriesId, season.id))
        val numberedIds = seasons.map { it.id }.toSet()
        return episodes(s, seriesId).filter { e ->
            val season = e.item.seasonId
            (if (season == null) true else season in numberedIds) && e.item.parentIndexNumber != 0
        }
    }

    private suspend fun episodes(s: Session, seriesId: String) = items.getEpisodes(s.serverId, s.userId, seriesId, null).map { it.toModel() }

    private suspend fun visible(s: Session, itemId: String): Item? = items.getItem(s.serverId, s.userId, itemId)?.toModel()?.item

    /** [here]: the quality of the copy already on this phone (any owner), or null. */
    private suspend fun decide(s: Session, item: Item, facts: DownloadFacts?, here: DownloadQuality?, quality: DownloadQuality, gate: Semaphore): Outcome {
        fun skip(reason: SkipReason) = Outcome.Skip(Skipped(item.id, reason))
        if (facts == null) return skip(SkipReason.NOT_FOUND)
        if (facts.canDownload == false) return skip(SkipReason.NOT_ALLOWED)
        // Unknown allows the original only: /Download enforces the server's own permission, /videos/ does not (Task 1).
        val conversionsAllowed = facts.canDownload == true
        if (here != null) {
            // Already on this phone: confirming only adds this profile as an owner, and nothing asks /Download, so only an
            // explicit yes for THIS profile counts (Tasks 5-8 review I-1): unknown is not a confirmation.
            if (!conversionsAllowed) return skip(SkipReason.PERMISSION_UNCONFIRMED)
            return Outcome.Planned(PlannedItem(item.id, here, 0, shareOnly = true))
        }
        if (quality != DownloadQuality.ORIGINAL) {
            return if (conversionsAllowed) Outcome.Planned(PlannedItem(item.id, quality, estimate(quality, item.runtimeMs), false))
            else skip(SkipReason.CONVERSION_UNCONFIRMED)
        }
        val check = try {
            OriginalChecks.of(gate.withPermit { checkOriginal(s, item.id) })
        } catch (e: AppError) {
            when (e.kind) {
                AppError.Kind.FORBIDDEN, AppError.Kind.PLAYBACK_NOT_ALLOWED -> return skip(SkipReason.NOT_ALLOWED)
                AppError.Kind.SERVER_NOT_FOUND -> return skip(SkipReason.NOT_FOUND)
                AppError.Kind.NO_COMPATIBLE_STREAM -> OriginalCheck.NotPlayableHere
                else -> throw e
            }
        }
        return when (check) {
            is OriginalCheck.Playable -> Outcome.Planned(
                PlannedItem(item.id, DownloadQuality.ORIGINAL, facts.sizeBytes ?: check.sizeBytes ?: originalEstimate(item.runtimeMs), false),
            )
            OriginalCheck.NotPlayableHere ->
                if (!conversionsAllowed) skip(SkipReason.CONVERSION_UNCONFIRMED)
                else Outcome.Planned(PlannedItem(item.id, DownloadQuality.HIGH, estimate(DownloadQuality.HIGH, item.runtimeMs), false), converted = true)
        }
    }

    private fun estimate(quality: DownloadQuality, runtimeMs: Long?): Long = bytesAt(checkNotNull(quality.quality).maxBitrate.toLong(), runtimeMs)

    private fun originalEstimate(runtimeMs: Long?): Long = bytesAt(ORIGINAL_BITRATE, runtimeMs)

    /** [bitsPerSecond] / 8 × the runtime in seconds; an unknown runtime (none, or 0) counts as 45 minutes (review Minor 7). */
    private fun bytesAt(bitsPerSecond: Long, runtimeMs: Long?): Long =
        bitsPerSecond / 8 * ((runtimeMs?.takeIf { it > 0 } ?: UNKNOWN_RUNTIME_MS) / 1_000)

    private fun saturatingAdd(a: Long, b: Long): Long = if (b > 0 && a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private companion object {
        const val FACTS_CHUNK = 50
        const val ORIGINAL_BITRATE = 25_000_000L
        const val UNKNOWN_RUNTIME_MS = 45L * 60 * 1_000
        const val INTERNAL_RESERVE = 1L shl 30
        const val REMOVABLE_RESERVE = 256L shl 20
    }
}
