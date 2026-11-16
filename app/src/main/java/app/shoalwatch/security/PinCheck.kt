package app.shoalwatch.security

/** The result of checking a profile PIN. Holds no PIN and no profile. */
sealed interface PinCheck {
    data object Correct : PinCheck

    /**
     * [triesLeft] wrong PINs remain before a wait starts. At 0 the free tries are used up and the wait has started with this
     * wrong PIN (the next `check` answers [Wait]).
     */
    data class Wrong(val triesLeft: Int) : PinCheck

    /** Nothing was checked: wait this long first. */
    data class Wait(val seconds: Int) : PinCheck

    /** The verifier can no longer be opened on this phone (the Keystore key is gone): only "Forgot PIN" helps. */
    data object Unreadable : PinCheck

    data object NotProtected : PinCheck
}
