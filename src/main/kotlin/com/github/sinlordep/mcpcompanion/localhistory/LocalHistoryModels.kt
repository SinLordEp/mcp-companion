package com.github.sinlordep.mcpcompanion.localhistory

import com.github.sinlordep.mcpcompanion.common.diff.TextDiff
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/**
 * A label this tool put into the IDE's Local History: [id] is the IDE's label id, [timestamp] when it was put (epoch
 * milliseconds). The IDE drops labels with the history they belong to (after a few days by default).
 */
data class HistoryLabel(val id: String, val name: String, val timestamp: Long)

/**
 * What happened to one file between a past state and now. [NO_EARLIER_VERSION]: the file changed since a point in
 * time, but the history holds no version of it from before that time (it was created later, or changed for the first
 * time in the IDE since then); a label always knows.
 */
enum class FileChangeKind { MODIFIED, CREATED, DELETED, NO_EARLIER_VERSION }

/**
 * One file that differs between a past state and now, with the [diff] of its text; [diff] is null for binary files and
 * for files past the line budget ([omitted]).
 */
data class HistoryFileChange(
    val path: String,
    val kind: FileChangeKind,
    val binary: Boolean,
    val diff: TextDiff?,
    val omitted: Boolean,
)

/**
 * The files under a path that differ between a past state and now. [scanned] files were compared; [complete] is
 * false when the directory had more files than one call compares.
 */
data class HistoryComparison(
    val path: String,
    val changes: List<HistoryFileChange>,
    val scanned: Int,
    val complete: Boolean,
)

/** Which way a diff reads: from the past state to now (`diff`), or from now back to it (`preview` of `revert`). */
enum class DiffDirection { SINCE_THEN, BACK_TO_THEN }

/** The outcome of a revert. */
sealed interface RevertAttempt {
    /** Done as the undoable command [command]; [changedFiles] are the paths it wrote (null: not known). */
    data class Reverted(val command: String?, val changedFiles: List<String>?) : RevertAttempt

    /** Nothing differs from the past state; nothing was done. */
    data object NothingToRevert : RevertAttempt
}

/**
 * Gateway boundary: the IDE's Local History through its public API (labels, the content of a file at a point in
 * time, the revert to a label). Never opens a dialog. Paths are client paths (relative to the project root or
 * absolute) and come back in the tool's output form.
 */
interface LocalHistoryGateway {

    /** Puts a label named [name] into the project's Local History and records it. */
    suspend fun putLabel(name: String): ToolResult<HistoryLabel>

    /** The labels this tool put in the project that the IDE still keeps, newest first. */
    suspend fun labels(): List<HistoryLabel>

    /**
     * The project files at [path] (a file, or a directory with everything under it) whose content at [target]
     * differs from now, read in [direction], with diffs of at most [maxLines] lines in all.
     */
    suspend fun compare(
        path: String,
        target: HistoryTarget,
        direction: DiffDirection,
        maxLines: Int,
    ): ToolResult<HistoryComparison>

    /** Restores the content the file [path] had at [target], as one undoable command named after [description]. */
    suspend fun restoreFile(path: String, target: HistoryTarget, description: String): ToolResult<RevertAttempt>

    /** Reverts [path] (a file or a directory) to [label] with the IDE's own revert. */
    suspend fun revertToLabel(path: String, label: HistoryLabel): ToolResult<RevertAttempt>

    /** Whether [path] is a directory; a failure when there is no project file or directory at [path]. */
    suspend fun isDirectory(path: String): ToolResult<Boolean>
}
