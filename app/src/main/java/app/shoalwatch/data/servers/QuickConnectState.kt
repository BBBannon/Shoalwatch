package app.shoalwatch.data.servers

import app.shoalwatch.core.AppError

/** Progress of a Quick Connect sign-in; see [AuthRepository.quickConnect]. */
sealed interface QuickConnectState {
    /** Asking the server whether Quick Connect is on and requesting a code. */
    data object Starting : QuickConnectState

    /** Show [code] to the user; they approve it in another Jellyfin client (Settings, Quick Connect). */
    data class AwaitingApproval(val code: String) : QuickConnectState

    /** The code was approved; exchanging it for an access token. */
    data object Authenticating : QuickConnectState

    /** Terminal: signed in. [account] has been saved and made active. */
    data class Success(val account: Account) : QuickConnectState

    /** Terminal: the code was not approved in time. Start again for a new code. */
    data object TimedOut : QuickConnectState

    /** Terminal: something went wrong; show `error.userMessage`. */
    data class Failed(val error: AppError) : QuickConnectState
}
