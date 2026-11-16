package app.shoalwatch.data.servers

/** What [ServerStore.token] found for one account. Never prints the token. */
sealed interface StoredToken {
    /** No such account: nothing to revoke. */
    data object Missing : StoredToken

    /** The account is there but its token cannot be opened (lost Keystore key): it cannot be revoked. */
    data object Unreadable : StoredToken

    class Found(val token: String) : StoredToken {
        override fun toString() = "StoredToken.Found(<redacted>)"
    }
}

/** What [ServerStore.tokens] found for one server: tokens that can be revoked, and a count of those that cannot be read. */
class StoredTokens(val readable: List<Pair<String, String>>, val unreadable: Int) {
    override fun toString() = "StoredTokens(readable=${readable.size}, unreadable=$unreadable)"
}
