package app.shoalwatch.data.admin.api

import app.shoalwatch.data.servers.JellyfinId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Switching a user on or off without resetting anything else. POST /Users/{userId}/Policy replaces the whole policy, so the
 * body is the server's own `Policy` object as read, with only `IsDisabled` changed: keys this app does not know (future
 * ones too) go back exactly as they came. Pure; see "Server admin" in docs/ARCHITECTURE.md.
 */
internal object UserPolicyEdit {
    /** The largest GET /Users/{userId} answer read for an edit. */
    const val MAX_POLICY_BYTES = 256L * 1024

    private val REQUIRED = listOf("AuthenticationProviderId", "PasswordResetProviderId")

    /**
     * The `Policy` object of a `UserDto` read from GET /Users/{userId}, with only `IsDisabled` set to [disabled]; every other key
     * kept exactly as the server sent it (unknown ones too). Null — so nothing is posted — when the user's `Id` is not [userId],
     * there is no `Policy` object, or `AuthenticationProviderId` / `PasswordResetProviderId` (required by the 12.4.0 schema) is
     * missing or not a string.
     */
    fun withDisabled(user: JsonObject, userId: String, disabled: Boolean): JsonObject? {
        val answered = (user["Id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(JellyfinId::parseOrNull) ?: return null
        if (answered != JellyfinId.parseOrNull(userId)) return null
        val policy = user["Policy"] as? JsonObject ?: return null
        if (REQUIRED.any { key -> (policy[key] as? JsonPrimitive)?.isString != true }) return null
        return JsonObject(LinkedHashMap(policy).apply { put("IsDisabled", JsonPrimitive(disabled)) })
    }
}
