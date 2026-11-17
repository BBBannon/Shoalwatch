package app.shoalwatch.security

import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Session

/**
 * The only key to [ProfileLocks.clear] (Tasks 1-2 review M1): proof that the locked profile's own server password was just
 * accepted ("Forgot PIN" on the lock screen). It is made only from the account the server returned for that password, and only
 * when that account is exactly the locked profile. `ClearProofBoundaryTest` keeps [afterPasswordSignIn] in the lock screen's
 * ViewModel, right after `signInWithPassword`, and `clear` in `LockParts`.
 */
class ClearProof private constructor(val session: Session) {
    override fun toString() = "ClearProof(<redacted>)"

    companion object {
        /** Null unless [signedIn] (what the server answered for the password) is exactly [locked]. */
        fun afterPasswordSignIn(locked: Session, signedIn: AccountSummary): ClearProof? =
            if (signedIn.serverId == locked.serverId && signedIn.userId == locked.userId) ClearProof(locked) else null
    }
}
