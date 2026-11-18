package app.shoalwatch.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** The dates a server's answer may hold; anything outside is no date at all (security review M8 F3). */
val SERVER_DATES: ClosedRange<Instant> = Instant.parse("1800-01-01T00:00:00Z")..Instant.parse("2200-01-01T00:00:00Z")

/**
 * A date from the server. Lenient about form (Jellyfin's seven fractional digits, or an offset) and strict about range: an
 * unreadable date, or one outside [SERVER_DATES], is null, never a crash. `Instant` itself holds years up to a billion either way,
 * which `toEpochMilli()` and `atZone()` cannot take.
 */
fun serverInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank() || raw.length > 64) return null
    val parsed = try {
        Instant.parse(raw)
    } catch (_: DateTimeParseException) {
        try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }
    return parsed?.takeIf { it in SERVER_DATES }
}
