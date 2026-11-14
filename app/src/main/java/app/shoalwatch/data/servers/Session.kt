package app.shoalwatch.data.servers

/** Who a request is made as. Ids are normalised `JellyfinId` strings; nothing here is secret, but it is never printed. */
data class Session(val serverId: String, val userId: String) {
    override fun toString() = "Session(<redacted>)"
}
