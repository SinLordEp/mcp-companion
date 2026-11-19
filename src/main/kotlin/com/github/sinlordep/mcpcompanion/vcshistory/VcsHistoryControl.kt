package com.github.sinlordep.mcpcompanion.vcshistory

import com.github.sinlordep.mcpcompanion.common.actions.ActionRegistry
import com.github.sinlordep.mcpcompanion.common.actions.ParameterGuard
import com.github.sinlordep.mcpcompanion.common.actions.ParameterizedAction
import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** One `mc_vcs_history` action; arguments outside [parameters] are rejected before [handle]. */
interface VcsHistoryActionHandler : ParameterizedAction<VcsHistoryParameter, VcsHistoryArguments, VcsHistoryOutcome> {
    val action: VcsHistoryAction

    override val actionName: String get() = action.name.lowercase()
}

/** Consecutive lines [startLine]..[endLine] (1-based) last changed in [revision] (null: not committed). */
data class BlameSegment(val startLine: Int, val endLine: Int, val revision: String?)

/** What an `mc_vcs_history` action answers. */
sealed interface VcsHistoryOutcome {
    /**
     * `log`: one page of the revisions of [path], newest first. With [lines], only the revisions that last changed
     * those lines (from the annotation).
     */
    data class Logged(val path: String, val vcs: String, val lines: IntRange?, val revisions: Page<CommitInfo>) :
        VcsHistoryOutcome

    /** `blame`: one page of the line [segments] of [blame], with the [commits] they name. */
    data class Blamed(
        val blame: FileBlame,
        val lines: IntRange,
        val segments: Page<BlameSegment>,
        val commits: List<CommitInfo>,
    ) : VcsHistoryOutcome

    /** `diff`: one file's diff between two versions. */
    data class Diffed(val diff: FileDiff) : VcsHistoryOutcome
}

/** Use case of `mc_vcs_history`: dispatches each action to its handler after checking the arguments. */
class VcsHistoryControl(gateway: VcsHistoryGateway) {

    private val actions = ActionRegistry(
        listOf(LogRevisions(gateway), BlameLines(gateway), DiffRevisions(gateway))
            .associate { it.action to ParameterGuard(it, VcsHistoryParameter::label) },
    )

    suspend fun execute(action: VcsHistoryAction, arguments: VcsHistoryArguments): ToolResult<VcsHistoryOutcome> =
        actions.dispatch(action, arguments)

    companion object {
        const val DEFAULT_LOG_LIMIT: Int = 20
        const val MAX_LOG_LIMIT: Int = 200
        const val DEFAULT_BLAME_LIMIT: Int = 100
        const val DEFAULT_MAX_LINES: Int = 400
        const val MAX_LINES: Int = 5_000
    }
}
