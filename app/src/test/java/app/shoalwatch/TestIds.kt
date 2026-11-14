package app.shoalwatch

import java.security.MessageDigest

/** A valid (32 lower-case hex digits) Jellyfin id derived from [label], so tests can keep readable names. */
fun testId(label: String): String =
    MessageDigest.getInstance("MD5").digest(label.toByteArray()).joinToString("") { "%02x".format(it) }
