package me.lesh.chatter.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspending code. A cancelled coroutine has to stop; caught as a failure, it
 * would go on and act on an error that never happened, e.g. show an empty search result.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
