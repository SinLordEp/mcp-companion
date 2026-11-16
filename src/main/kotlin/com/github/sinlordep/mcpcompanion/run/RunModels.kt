package com.github.sinlordep.mcpcompanion.run

import java.time.Instant

/**
 * Opaque handle of one process the IDE runs for a project: `p` and a sequence number that is unique per project for
 * the lifetime of the IDE. It never changes and is never reused, so two runs of the same configuration have different
 * handles. It stays valid while the process is retained (see `ProcessTracker`).
 */
@JvmInline
value class ProcessId(val value: String) {
    override fun toString(): String = value

    companion object {
        private val SYNTAX = Regex("p[1-9][0-9]{0,17}")

        fun of(sequence: Long): ProcessId = ProcessId("p$sequence")

        /** Null when [text] is not a handle this plugin hands out. */
        fun parse(text: String): ProcessId? = text.trim().takeIf(SYNTAX::matches)?.let(::ProcessId)
    }
}

/** How a process was started. [OTHER] covers further executors (coverage, profiler, ...); only run and debug start. */
enum class ExecutorKind { RUN, DEBUG, OTHER }

/** Lifecycle of a tracked process. */
sealed interface ProcessState {
    /** Scheduled: before-launch tasks (e.g. Build) run, or the process is being launched. */
    data object Starting : ProcessState

    data object Running : ProcessState

    /** Asked to stop and not ended yet. */
    data object Terminating : ProcessState

    data class Terminated(val exitCode: Int) : ProcessState

    /** The IDE gave up before a process existed, e.g. a before-launch build failed. [reason] when the IDE gave one. */
    data class NotStarted(val reason: String?) : ProcessState

    val isAlive: Boolean get() = this == Starting || this == Running || this == Terminating

    val hasEnded: Boolean get() = !isAlive
}

/**
 * An existing run configuration. [key] is the IDE's unique id (type and name); a name alone may be ambiguous across
 * configuration types.
 */
data class ConfigurationRef(
    val key: String,
    val name: String,
    val typeId: String,
    val typeName: String,
    val folder: String?,
    val allowsParallelRuns: Boolean,
)

/** Immutable view of a tracked process at one moment. */
data class ProcessSnapshot(
    val id: ProcessId,
    val name: String,
    val configuration: ConfigurationRef?,
    val executor: ExecutorKind,
    val executorId: String,
    val state: ProcessState,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val pid: Long? = null,
    /** False when tracking began after the process had started (it ran before the plugin was loaded). */
    val outputComplete: Boolean = true,
)
