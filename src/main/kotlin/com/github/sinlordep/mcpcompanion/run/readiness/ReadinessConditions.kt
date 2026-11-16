package com.github.sinlordep.mcpcompanion.run.readiness

import com.github.sinlordep.mcpcompanion.run.Clock
import com.github.sinlordep.mcpcompanion.run.PortProbe
import com.github.sinlordep.mcpcompanion.run.ProcessState
import com.github.sinlordep.mcpcompanion.run.TrackedProcess
import com.github.sinlordep.mcpcompanion.run.output.ConsoleBuffer
import com.github.sinlordep.mcpcompanion.run.output.ConsoleLine
import com.github.sinlordep.mcpcompanion.run.output.ConsoleSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlin.time.Duration

/** Something that ends `wait_ready`. */
sealed interface ReadinessSignal {
    /** A line matched a ready pattern, or a failure pattern when [failure]. */
    data class OutputMatched(val failure: Boolean, val pattern: String, val line: ConsoleLine) : ReadinessSignal

    data class ProcessEnded(val state: ProcessState) : ReadinessSignal

    data class PortOpen(val port: Int) : ReadinessSignal
}

/** One readiness strategy: a cold flow that emits when its condition holds (strategy pattern). */
fun interface ReadinessCondition {
    fun signals(process: TrackedProcess): Flow<ReadinessSignal>
}

/**
 * Ready or failure patterns in the console, from the line after [since] on, including unfinished lines (a prompt
 * without a newline). On one line a failure pattern wins; earlier lines win over later ones. Event-driven: re-reads
 * the console whenever it changes.
 */
class OutputCondition(private val ready: List<Regex>, private val failure: List<Regex>, private val since: Long) :
    ReadinessCondition {

    override fun signals(process: TrackedProcess): Flow<ReadinessSignal> = flow {
        var cursor = since
        process.console.version.transformWhile {
            val snapshot = process.console.snapshotAfter(cursor)
            val match = firstMatch(snapshot)
            cursor = snapshot.lastSeq
            if (match != null) emit(match)
            match == null
        }.collect { emit(it) }
    }

    /** The first matching line of [console] after `since`, as it is now. */
    fun firstMatch(console: ConsoleBuffer): ReadinessSignal.OutputMatched? = firstMatch(console.snapshotAfter(since))

    /** The first matching line of [snapshot], complete lines before unfinished ones. */
    private fun firstMatch(snapshot: ConsoleSnapshot): ReadinessSignal.OutputMatched? =
        (snapshot.lines.asSequence() + snapshot.partials.asSequence()).firstNotNullOfOrNull(::match)

    private fun match(line: ConsoleLine): ReadinessSignal.OutputMatched? {
        failure.firstOrNull { it.containsMatchIn(line.text) }?.let {
            return ReadinessSignal.OutputMatched(true, it.pattern, line)
        }
        return ready.firstOrNull { it.containsMatchIn(line.text) }?.let {
            ReadinessSignal.OutputMatched(false, it.pattern, line)
        }
    }
}

/** The process ended or never started. Always part of a wait: a dead process never becomes ready. */
object TerminationCondition : ReadinessCondition {
    override fun signals(process: TrackedProcess): Flow<ReadinessSignal> = flow {
        emit(ReadinessSignal.ProcessEnded(process.snapshots.first { it.state.hasEnded }.state))
    }
}

/**
 * A TCP connection to [port] on the loopback interface succeeds. There is no event for that, so this strategy probes
 * every [interval] on the injected clock (suspending, cancellable); it is only used when the caller asks for a port.
 */
class PortCondition(
    private val port: Int,
    private val probe: PortProbe,
    private val clock: Clock,
    private val interval: Duration,
) : ReadinessCondition {

    override fun signals(process: TrackedProcess): Flow<ReadinessSignal> = flow {
        while (!probe.isOpen(port)) clock.sleep(interval)
        emit(ReadinessSignal.PortOpen(port))
    }
}

/** Patterns used when the caller gives none: start messages of common JVM servers, and Spring Boot's failure. */
object DefaultPatterns {
    val READY: List<Regex> = listOf(
        Regex("""Started \S+ in [0-9.]+ seconds"""),
        Regex("""started in [0-9.]+s\. Listening on"""),
        Regex("""Startup completed in \d+ms"""),
        Regex("""(?i)\b(listening on|server started|ready to accept connections)\b"""),
    )

    val FAILURE: List<Regex> = listOf(Regex("""APPLICATION FAILED TO START"""))
}
