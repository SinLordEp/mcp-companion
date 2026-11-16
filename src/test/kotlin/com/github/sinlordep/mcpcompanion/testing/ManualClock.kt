package com.github.sinlordep.mcpcompanion.testing

import com.github.sinlordep.mcpcompanion.common.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** A clock that only moves when the test calls [advance]; [sleep] suspends until then. No real time passes. */
class ManualClock(start: Instant = Instant.parse("2026-11-16T10:00:00Z")) : Clock {
    private class Sleeper(val until: Instant, val wake: CompletableDeferred<Unit>)

    private var current = start
    private val sleepers = mutableListOf<Sleeper>()

    val sleeping: Int get() = synchronized(this) { sleepers.size }

    override fun now(): Instant = synchronized(this) { current }

    override suspend fun sleep(duration: Duration) {
        val sleeper = synchronized(this) { Sleeper(current + duration.toJavaDuration(), CompletableDeferred()) }
        synchronized(this) { sleepers += sleeper }
        try {
            sleeper.wake.await()
        } finally {
            synchronized(this) { sleepers -= sleeper }
        }
    }

    fun advance(duration: Duration) {
        val due = synchronized(this) {
            current += duration.toJavaDuration()
            sleepers.filter { it.until <= current }
        }
        due.forEach { it.wake.complete(Unit) }
    }
}

/** Lets the coroutines of a single-threaded test run until [condition] holds (bounded, no real waiting). */
suspend fun settleUntil(what: String, condition: () -> Boolean) {
    repeat(10_000) {
        if (condition()) return
        yield()
    }
    throw AssertionError("never happened: $what")
}
