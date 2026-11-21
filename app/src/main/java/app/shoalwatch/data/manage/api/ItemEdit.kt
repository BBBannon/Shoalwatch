package app.shoalwatch.data.manage.api

import app.shoalwatch.core.displayParagraphs
import app.shoalwatch.core.displayText
import app.shoalwatch.data.manage.LockableField
import app.shoalwatch.data.manage.ManageLimits
import app.shoalwatch.data.manage.MetadataEdit
import app.shoalwatch.data.manage.MetadataForm
import app.shoalwatch.data.servers.JellyfinId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * The full-item round trip behind "Edit details". `POST /Items/{id}` replaces the whole item, so the body is the server's own
 * item object as just read, copied key by key with only the typed fields this form edits replaced: every other key (known,
 * unknown or future), number literal, null and nested object goes back as it came. The values written are this app's own, made
 * from [MetadataEdit] (typed, bounded, cleaned), never text taken from the server and pasted back. Only the keys whose typed value
 * differs from the form as it was shown ([changedKeys]) are replaced, so a value the form cannot show exactly (a rating with two
 * decimals, an overview the display trimmed, a studio with a comma in its name) goes back as it came unless it was changed.
 */
internal object ItemEdit {
    const val MAX_ITEM_BYTES = 4L * 1024 * 1024

    /** The form as the server has it now, or null when [item]'s `Id` is not [itemId] (nothing is shown for another item). */
    fun form(item: JsonObject, itemId: String, ratingOptions: List<String>): MetadataForm? {
        if (JellyfinId.parseOrNull(item.string("Id"))?.value != itemId) return null
        val locked = item.strings("LockedFields", 20).mapNotNull { w -> LockableField.entries.firstOrNull { it.wire.equals(w, ignoreCase = true) } }.toSet()
        return MetadataForm(
            name = displayText(item.string("Name"), ManageLimits.NAME),
            sortName = displayText(item.string("ForcedSortName"), ManageLimits.NAME),
            originalTitle = displayText(item.string("OriginalTitle"), ManageLimits.NAME),
            overview = displayParagraphs(item.string("Overview"), ManageLimits.OVERVIEW),
            year = wholeNumber(item["ProductionYear"], ManageLimits.YEAR_MIN.toLong()..ManageLimits.YEAR_MAX.toLong())?.toInt(),
            premiereDate = day(item.string("PremiereDate"))?.takeIf { it.year in ManageLimits.YEAR_MIN..ManageLimits.YEAR_MAX },
            officialRating = displayText(item.string("OfficialRating"), ManageLimits.RATING),
            communityRating = decimal(item["CommunityRating"], 0.0..10.0),
            genres = item.strings("Genres", ManageLimits.LIST_SIZE).map { displayText(it, ManageLimits.LIST_ENTRY) }.filter { it.isNotEmpty() }.distinct(),
            tags = item.strings("Tags", ManageLimits.LIST_SIZE).map { displayText(it, ManageLimits.LIST_ENTRY) }.filter { it.isNotEmpty() }.distinct(),
            studios = studioNames(item).map { displayText(it, ManageLimits.LIST_ENTRY) }.filter { it.isNotEmpty() }.distinct(),
            locked = locked,
            lockAll = (item["LockData"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true",
            ratingOptions = ratingOptions,
        )
    }

    /**
     * The body to post: [item] with the edited keys replaced. Null (nothing is posted) when [item]'s `Id` is not [itemId] or it
     * has no `Type` (the server needs the whole item; something that is not one is not sent back).
     */
    fun withEdit(item: JsonObject, itemId: String, edit: MetadataEdit, shown: MetadataForm): JsonObject? {
        if (JellyfinId.parseOrNull(item.string("Id"))?.value != itemId) return null
        if (item.string("Type").isNullOrEmpty()) return null
        val clean = clean(edit)
        val changed = changedKeys(edit, shown)
        val replaced = mapOf<String, JsonElement>(
            "Name" to JsonPrimitive(clean.name),
            "ForcedSortName" to textOrNull(clean.sortName),
            "OriginalTitle" to textOrNull(clean.originalTitle),
            "Overview" to textOrNull(clean.overview),
            "ProductionYear" to (clean.year?.let(::JsonPrimitive) ?: JsonNull),
            "PremiereDate" to (clean.premiereDate?.let { JsonPrimitive(wireDate(it)) } ?: JsonNull),
            "OfficialRating" to textOrNull(clean.officialRating),
            "CommunityRating" to (clean.communityRating?.let { JsonPrimitive(BigDecimal(it).setScale(1, RoundingMode.HALF_UP)) } ?: JsonNull),
            "Genres" to JsonArray(clean.genres.map(::JsonPrimitive)),
            "Tags" to JsonArray(clean.tags.map(::JsonPrimitive)),
            "Studios" to JsonArray(clean.studios.map { name -> buildJsonObject { put("Name", name) } }),
            "LockedFields" to JsonArray(LockableField.entries.filter { it in clean.locked }.map { JsonPrimitive(it.wire) }),
            "LockData" to JsonPrimitive(clean.lockAll),
        ).filterKeys { it in changed }
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in item) out[key] = replaced[key] ?: value
        for ((key, value) in replaced) if (key !in out) out[key] = value
        return JsonObject(out)
    }

    /** The item keys whose typed value in [edit] differs from the form as [shown] (compared before cleaning: as the form held them). */
    fun changedKeys(edit: MetadataEdit, shown: MetadataForm): Set<String> = buildSet {
        if (edit.name != shown.name) add("Name")
        if (edit.sortName != shown.sortName) add("ForcedSortName")
        if (edit.originalTitle != shown.originalTitle) add("OriginalTitle")
        if (edit.overview != shown.overview) add("Overview")
        if (edit.year != shown.year) add("ProductionYear")
        if (edit.premiereDate != shown.premiereDate) add("PremiereDate")
        if (edit.officialRating != shown.officialRating) add("OfficialRating")
        if (edit.communityRating != shown.communityRating) add("CommunityRating")
        if (edit.genres != shown.genres) add("Genres")
        if (edit.tags != shown.tags) add("Tags")
        if (edit.studios != shown.studios) add("Studios")
        if (edit.locked != shown.locked) add("LockedFields")
        if (edit.lockAll != shown.lockAll) add("LockData")
    }

    /** What is actually written: every text cleaned and bounded, lists without blanks or repeats, numbers in range. */
    fun clean(edit: MetadataEdit): MetadataEdit = edit.copy(
        name = displayText(edit.name, ManageLimits.NAME),
        sortName = displayText(edit.sortName, ManageLimits.NAME),
        originalTitle = displayText(edit.originalTitle, ManageLimits.NAME),
        overview = displayParagraphs(edit.overview, ManageLimits.OVERVIEW),
        year = edit.year?.takeIf { it in ManageLimits.YEAR_MIN..ManageLimits.YEAR_MAX },
        premiereDate = edit.premiereDate?.takeIf { it.year in ManageLimits.YEAR_MIN..ManageLimits.YEAR_MAX },
        officialRating = displayText(edit.officialRating, ManageLimits.RATING),
        communityRating = edit.communityRating?.takeIf { it.isFinite() && it in 0.0..10.0 },
        genres = list(edit.genres),
        tags = list(edit.tags),
        studios = list(edit.studios),
    )

    private fun list(raw: List<String>) =
        raw.map { displayText(it, ManageLimits.LIST_ENTRY) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(ManageLimits.LIST_SIZE)

    /** Jellyfin's own form for a date with no time: midnight UTC. */
    fun wireDate(d: LocalDate): String = "${d}T00:00:00.0000000Z"

    private fun textOrNull(s: String): JsonElement = if (s.isEmpty()) JsonNull else JsonPrimitive(s)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.strings(key: String, cap: Int): List<String> =
        (this[key] as? JsonArray).orEmpty().asSequence().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.take(cap).toList()

    private fun studioNames(item: JsonObject): List<String> = (item["Studios"] as? JsonArray).orEmpty().asSequence()
        .mapNotNull { ((it as? JsonObject)?.get("Name") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        .take(ManageLimits.LIST_SIZE).toList()
}
