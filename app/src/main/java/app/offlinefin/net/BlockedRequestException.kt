package app.offlinefin.net

import app.offlinefin.core.AppError
import java.io.IOException

/**
 * How a request guard reports a refusal. OkHttp only hands an [IOException] to callers of
 * `enqueue` (any other throwable becomes a generic "canceled" IOException with ours merely
 * suppressed), so the [AppError] travels as the cause. `AppError.from` unwraps it.
 */
class BlockedRequestException(val error: AppError) : IOException(error.userMessage, error)
