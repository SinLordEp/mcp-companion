package com.github.sinlordep.mcpcompanion.toolsets.sync

import com.github.sinlordep.mcpcompanion.sync.BuildSystemStatus
import com.github.sinlordep.mcpcompanion.sync.IndexingWait
import com.github.sinlordep.mcpcompanion.sync.LastSync
import com.github.sinlordep.mcpcompanion.sync.SyncOutcome
import com.github.sinlordep.mcpcompanion.sync.SyncResult
import com.github.sinlordep.mcpcompanion.sync.UnitStatus
import com.github.sinlordep.mcpcompanion.sync.UnitSync
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration

/** MCP output model of `mc_sync`: status sets buildSystems, sync sets syncs, indexing and waitedMs. */
@Serializable
data class SyncOutput(
    val buildSystems: List<BuildSystemOutput>? = null,
    val syncs: List<UnitSyncOutput>? = null,
    /** not_requested, done (no configuration activity and smart mode after the syncs), timed_out or skipped. */
    val indexing: String? = null,
    val waitedMs: Long? = null,
) {
    companion object {
        fun from(outcome: SyncOutcome): SyncOutput = when (outcome) {
            is SyncOutcome.Status -> SyncOutput(buildSystems = outcome.buildSystems.map(BuildSystemOutput::from))

            is SyncOutcome.Synced -> SyncOutput(
                syncs = outcome.units.map(UnitSyncOutput::from),
                indexing = when (outcome.indexing) {
                    IndexingWait.NOT_REQUESTED -> "not_requested"
                    IndexingWait.DONE -> "done"
                    IndexingWait.TIMED_OUT -> "timed_out"
                    IndexingWait.SKIPPED -> "skipped"
                },
                waitedMs = outcome.waitedMillis,
            )
        }
    }
}

/** needsReload: the IDE shows its "Load changes" notice for this build system; absent when it cannot tell. */
@Serializable
data class BuildSystemOutput(
    val id: String,
    val name: String,
    val needsReload: Boolean? = null,
    val projects: List<LinkedProjectOutput>,
) {
    companion object {
        fun from(status: BuildSystemStatus) = BuildSystemOutput(
            id = status.buildSystem.id,
            name = status.buildSystem.name,
            needsReload = status.needsReload,
            projects = status.units.map(LinkedProjectOutput::from),
        )
    }
}

/** paths: the linked project directories one sync covers. running: a sync runs now, whoever started it. */
@Serializable
data class LinkedProjectOutput(val paths: List<String>, val running: Boolean, val lastSync: RunOutput? = null) {
    companion object {
        fun from(status: UnitStatus) =
            LinkedProjectOutput(status.unit.projects, status.running, status.last?.let(RunOutput::from))
    }
}

/**
 * One sync. state: finished, running (the timeout passed; it goes on, see status) or not_started (the timeout passed
 * while another sync of these projects ran). joined: this call shared a sync another call started after this one
 * arrived.
 */
@Serializable
data class UnitSyncOutput(
    val buildSystem: String,
    val paths: List<String>,
    val state: State,
    val joined: Boolean? = null,
    val run: RunOutput? = null,
) {
    @Serializable
    enum class State {
        @SerialName("finished")
        FINISHED,

        @SerialName("running")
        RUNNING,

        @SerialName("not_started")
        NOT_STARTED,
    }

    companion object {
        fun from(sync: UnitSync) = UnitSyncOutput(
            buildSystem = sync.unit.buildSystem.id,
            paths = sync.unit.projects,
            state = State.valueOf(sync.state.name),
            joined = sync.joined.takeIf { it },
            run = sync.run?.let(RunOutput::from),
        )
    }
}

/**
 * After a failed sync the IDE may keep only part of the project model (a failed Gradle sync imports what it could
 * resolve; seen end to end), so modules can be missing until the next successful sync.
 */
private const val FAILED_NEXT: String = "Fix the build file named in message/details and run sync again. Until a " +
    "sync succeeds the IDE's project model may lack modules or dependencies; they are not gone."

/**
 * A sync mc_sync started. outcome (once ended): succeeded, failed or cancelled; message and details: the build tool's
 * reason for a failure; messages: problems reported without failing.
 */
@Serializable
data class RunOutput(
    val startedAt: String,
    val endedAt: String? = null,
    val durationMs: Long? = null,
    val outcome: String? = null,
    val message: String? = null,
    val details: String? = null,
    val messages: List<String>? = null,
    /** failed only: what to do about it. */
    val nextStep: String? = null,
) {
    companion object {
        fun from(run: LastSync): RunOutput {
            val record = run.record ?: return RunOutput(startedAt = run.startedAt.toString())
            val base = RunOutput(
                startedAt = record.startedAt.toString(),
                endedAt = record.endedAt.toString(),
                durationMs = Duration.between(record.startedAt, record.endedAt).toMillis(),
            )
            return when (val result = record.result) {
                is SyncResult.Succeeded ->
                    base.copy(outcome = "succeeded", messages = result.messages.ifEmpty { null })

                is SyncResult.Failed ->
                    base.copy(
                        outcome = "failed",
                        message = result.message,
                        details = result.details,
                        nextStep = FAILED_NEXT,
                    )

                SyncResult.Cancelled -> base.copy(outcome = "cancelled")
            }
        }
    }
}
