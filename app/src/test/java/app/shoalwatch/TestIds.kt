package app.shoalwatch

import java.security.MessageDigest

/** A valid (32 lower-case hex digits) Jellyfin id derived from [label], so tests can keep readable names. */
fun testId(label: String): String =
    MessageDigest.getInstance("MD5").digest(label.toByteArray()).joinToString("") { "%02x".format(it) }

/** The ids the store-backed tests use, where the literals "s1"/"u1" used to be. */
val S1 = testId("s1")
val S2 = testId("s2")
val U1 = testId("u1")
val U2 = testId("u2")
val U3 = testId("u3")
