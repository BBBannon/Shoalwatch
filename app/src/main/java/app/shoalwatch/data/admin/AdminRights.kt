package app.shoalwatch.data.admin

/** GET /Users/Me, the three things the administrator check reads. [userId] is a normalised `JellyfinId`, or null. */
internal data class AdminMe(val userId: String?, val isAdministrator: Boolean, val isDisabled: Boolean) {
    override fun toString() = "AdminMe(<redacted>)"
}
