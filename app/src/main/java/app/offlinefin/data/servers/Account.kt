package app.offlinefin.data.servers

/** A signed-in user. [accessToken] is a credential: never log it. */
data class Account(val serverId: String, val userId: String, val userName: String, val accessToken: String) {
    override fun toString() = "Account($userName@$serverId)"
}
