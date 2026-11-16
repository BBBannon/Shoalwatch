package app.shoalwatch.data.admin

import app.shoalwatch.data.admin.api.UserPolicyEdit
import app.shoalwatch.data.api.JsonBodies
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserPolicyEditTest {
    private val user = JsonBodies.JSON.parseToJsonElement(AdminFixtures.text("user-policy.json")).jsonObject
    private val policy = user.getValue("Policy").jsonObject

    private fun with(key: String, value: kotlinx.serialization.json.JsonElement?): JsonObject =
        JsonObject(user + ("Policy" to JsonObject(if (value == null) policy - key else policy + (key to value))))

    @Test fun everyKeyComesBackUnchangedExceptIsDisabled() {
        val edited = UserPolicyEdit.withDisabled(user, AdminFixtures.KID, disabled = true)!!
        assertEquals(policy.keys, edited.keys)
        for (key in policy.keys - "IsDisabled") assertEquals(key, policy[key], edited[key])
        assertEquals(JsonPrimitive(true), edited["IsDisabled"])
        assertEquals(JsonPrimitive(7), edited["MockFutureField"])
        assertEquals(policy["AccessSchedules"], edited["AccessSchedules"])
        assertEquals(policy["BlockedTags"], edited["BlockedTags"])
        assertEquals(JsonPrimitive(false), UserPolicyEdit.withDisabled(user, AdminFixtures.KID, disabled = false)!!["IsDisabled"])
    }

    @Test fun theFixtureHoldsEveryReferenceProperty() {
        val reference = JsonBodies.JSON.parseToJsonElement(java.io.File("../docs/reference/jellyfin-openapi-12.4.0.json").readText()).jsonObject
        val names = reference.getValue("components").jsonObject.getValue("schemas").jsonObject.getValue("UserPolicy").jsonObject
            .getValue("properties").jsonObject.keys
        assertEquals(emptySet<String>(), names - policy.keys)
    }

    @Test fun isDisabledIsAddedWhenAbsentAndNothingElse() {
        val edited = UserPolicyEdit.withDisabled(with("IsDisabled", null), AdminFixtures.KID, disabled = true)!!
        assertEquals(policy.keys, edited.keys)
    }

    @Test fun theUserIdMayBeDashedOrAnyCase() {
        val upper = JsonObject(user + ("Id" to JsonPrimitive("1D5E1F3B2A4C4E6F8A9B0C1D2E3F4A5B")))
        assertEquals(JsonPrimitive(true), UserPolicyEdit.withDisabled(upper, AdminFixtures.KID, disabled = true)!!["IsDisabled"])
    }

    @Test fun nothingIsPostedWhenTheAnswerIsNotUsable() {
        assertNull("no AuthenticationProviderId", UserPolicyEdit.withDisabled(with("AuthenticationProviderId", null), AdminFixtures.KID, true))
        assertNull("PasswordResetProviderId a number", UserPolicyEdit.withDisabled(with("PasswordResetProviderId", JsonPrimitive(3)), AdminFixtures.KID, true))
        assertNull("Policy an array", UserPolicyEdit.withDisabled(JsonObject(user + ("Policy" to JsonArray(emptyList()))), AdminFixtures.KID, true))
        assertNull("no Policy", UserPolicyEdit.withDisabled(JsonObject(user - "Policy"), AdminFixtures.KID, true))
        assertNull("another user", UserPolicyEdit.withDisabled(user, AdminFixtures.BEN, true))
        val other = JsonObject(user + ("Id" to JsonPrimitive("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")))
        assertNull("another user, dashed and upper case", UserPolicyEdit.withDisabled(other, AdminFixtures.KID, true))
        assertNull("no Id", UserPolicyEdit.withDisabled(JsonObject(user - "Id"), AdminFixtures.KID, true))
    }
}
