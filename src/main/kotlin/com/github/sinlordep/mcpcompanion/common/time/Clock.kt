package com.github.sinlordep.mcpcompanion.common.time

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Time source of the use cases, replaced by a manual clock in tests. */
interface Clock {
    fun now(): Instant

    /** Suspends for [duration]; cancellable. */
    suspend fun sleep(duration: Duration)
}

/** The real clock. */
object SystemClock : Clock {
    override fun now(): Instant = Instant.now()

    override suspend fun sleep(duration: Duration) = delay(duration)
}

/**
 * Runs [block] and returns its result, or null when [timeout] passes first on this clock. Event-driven: [block]
 * suspends on events and the clock only decides the deadline. Cancelling the caller cancels both.
 */
suspend fun <T : Any> Clock.withTimeout(timeout: Duration, block: suspend () -> T): T? = coroutineScope {
    val work = async { block() }
    val deadline = async { sleep(timeout) }
    select {
        work.onAwait { result ->
            deadline.cancel()
            result
        }
        deadline.onAwait {
            work.cancel()
            null
        }
    }
}

/** A client's `timeoutSeconds`, validated: [default] when absent, at most [max]. */
fun timeoutOf(seconds: Int?, default: Duration, max: Duration): ToolResult<Duration> {
    val value = seconds?.seconds ?: default
    return if (value.isPositive() && value <= max) {
        ToolResult.success(value)
    } else {
        ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "timeoutSeconds must be between 1 and ${max.inWholeSeconds}, was $seconds.",
            "Omit it to wait ${default.inWholeSeconds} s.",
        )
    }
}
