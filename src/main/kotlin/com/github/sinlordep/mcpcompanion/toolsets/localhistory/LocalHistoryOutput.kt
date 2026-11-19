package com.github.sinlordep.mcpcompanion.toolsets.localhistory

import com.github.sinlordep.mcpcompanion.localhistory.HistoryFileChange
import com.github.sinlordep.mcpcompanion.localhistory.HistoryLabel
import com.github.sinlordep.mcpcompanion.localhistory.HistoryTarget
import com.github.sinlordep.mcpcompanion.localhistory.LocalHistoryAction
import com.github.sinlordep.mcpcompanion.localhistory.LocalHistoryOutcome
import com.github.sinlordep.mcpcompanion.localhistory.RevertAttempt
import com.github.sinlordep.mcpcompanion.localhistory.pointInTime
import com.github.sinlordep.mcpcompanion.toolsets.TimeTexts
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `action`. */
@Serializable
enum class LocalHistoryActionInput(val action: LocalHistoryAction) {
    @SerialName("label")
    LABEL(LocalHistoryAction.LABEL),

    @SerialName("labels")
    LABELS(LocalHistoryAction.LABELS),

    @SerialName("diff")
    DIFF(LocalHistoryAction.DIFF),

    @SerialName("preview")
    PREVIEW(LocalHistoryAction.PREVIEW),

    @SerialName("revert")
    REVERT(LocalHistoryAction.REVERT),
}

/**
 * MCP output model of `mc_local_history`. `label`: [label]; `labels`: [labels]; `diff` and `preview`: [at],
 * [changes], [scannedFiles]; `revert`: [at], [reverted], [command] and [changedFiles]. [note] explains an empty or
 * partial answer.
 */
@Serializable
data class LocalHistoryOutput(
    val path: String? = null,
    val label: LabelOutput? = null,
    val labels: List<LabelOutput>? = null,
    /** The past state: ISO-8601 time, and the label when one was named. */
    val at: String? = null,
    val atLabel: String? = null,
    val changes: List<FileChangeOutput>? = null,
    val scannedFiles: Int? = null,
    val complete: Boolean? = null,
    val reverted: Boolean? = null,
    val command: String? = null,
    val changedFiles: List<String>? = null,
    val note: String? = null,
) {
    companion object {
        fun from(outcome: LocalHistoryOutcome): LocalHistoryOutput = when (outcome) {
            is LocalHistoryOutcome.Labelled -> LocalHistoryOutput(label = LabelOutput.from(outcome.label))

            is LocalHistoryOutcome.Labels -> LocalHistoryOutput(
                labels = outcome.labels.map(LabelOutput::from),
                note = NO_LABELS.takeIf { outcome.labels.isEmpty() },
            )

            is LocalHistoryOutcome.Changes -> LocalHistoryOutput(
                path = outcome.comparison.path,
                at = TimeTexts.iso(outcome.target.pointInTime),
                atLabel = (outcome.target as? HistoryTarget.AtLabel)?.label?.name,
                changes = outcome.comparison.changes.map(FileChangeOutput::from),
                scannedFiles = outcome.comparison.scanned,
                complete = outcome.comparison.complete.takeUnless { it },
                note = if (outcome.comparison.changes.isEmpty()) SAME_AS_THEN else DELETED_FILES,
            )

            is LocalHistoryOutcome.Reverted -> when (val attempt = outcome.attempt) {
                is RevertAttempt.Reverted -> LocalHistoryOutput(
                    path = outcome.path,
                    at = TimeTexts.iso(outcome.target.pointInTime),
                    atLabel = (outcome.target as? HistoryTarget.AtLabel)?.label?.name,
                    reverted = true,
                    command = attempt.command,
                    changedFiles = attempt.changedFiles,
                    note = IDE_REVERT.takeIf { attempt.changedFiles == null },
                )

                RevertAttempt.NothingToRevert -> LocalHistoryOutput(
                    path = outcome.path,
                    at = TimeTexts.iso(outcome.target.pointInTime),
                    reverted = false,
                    note = SAME_AS_THEN,
                )
            }
        }

        private const val NO_LABELS = "No labels: put one with action=label before a change you may want to undo."
        private const val SAME_AS_THEN = "Nothing differs from that state."
        private const val DELETED_FILES = "Files deleted since then are not listed; reverting a directory to a " +
            "label restores them."
        private const val IDE_REVERT = "The IDE's revert restored everything under path to the label (deleted " +
            "files too); Edit | Undo in the IDE reverts it."
    }
}

/** A label of this tool; [time] ISO-8601 in the IDE's time zone. */
@Serializable
data class LabelOutput(val name: String, val time: String, val id: String) {
    companion object {
        fun from(label: HistoryLabel) = LabelOutput(label.name, TimeTexts.iso(label.timestamp), label.id)
    }
}

/**
 * One file that differs: [change] is modified, created or deleted (`diff`: what happened since then; `preview`: what
 * the revert does). [diff] is a unified diff; [truncated] when cut at maxLines, [omitted] when no lines were left.
 */
@Serializable
data class FileChangeOutput(
    val path: String,
    val change: String,
    val diff: String? = null,
    val added: Int? = null,
    val removed: Int? = null,
    val truncated: Boolean? = null,
    val binary: Boolean? = null,
    val omitted: Boolean? = null,
) {
    companion object {
        fun from(change: HistoryFileChange) = FileChangeOutput(
            path = change.path,
            change = change.kind.name.lowercase(),
            diff = change.diff?.text,
            added = change.diff?.added,
            removed = change.diff?.removed,
            truncated = change.diff?.truncated?.takeIf { it },
            binary = change.binary.takeIf { it },
            omitted = change.omitted.takeIf { it },
        )
    }
}
