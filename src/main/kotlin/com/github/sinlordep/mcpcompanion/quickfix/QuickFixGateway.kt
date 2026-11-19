package com.github.sinlordep.mcpcompanion.quickfix

import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** Opaque state a gateway needs to compute the fixes of a problem. Use cases pass it on without looking inside. */
interface ProblemHandle

/** Opaque state a gateway needs to apply a fix. Use cases pass it on without looking inside. */
interface FixHandle

/**
 * A file the IDE analysed as an editor does. [contentStamp] identifies the file's content at the analysis: fix ids
 * are made from it, so they change with any edit of the file.
 */
class AnalysedFile(val path: String, val contentStamp: String, val problems: List<AnalysedProblem>)

/**
 * A problem the IDE reports in a file. Offsets (0-based, in the file's text) identify it within one content; lines
 * and columns are 1-based. [lineText] is the start line, shortened.
 */
class AnalysedProblem(
    val severity: ProblemSeverity,
    val message: String,
    val startOffset: Int,
    val endOffset: Int,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val lineText: String,
    val handle: ProblemHandle,
)

/**
 * One fix the IDE offers for a problem (what Alt+Enter lists for it). [position] is its place in the IDE's list:
 * `2`, or `2.1` for the first choice inside fix 2 when that fix lets the user choose (e.g. which class to import).
 * [refusal]: why this tool cannot apply it without the user (it opens a dialog, asks for template input, works only
 * in an open editor, changes files outside the project, ...); null when it can.
 */
class AnalysedFix(val position: String, val name: String, val refusal: String?, val handle: FixHandle)

/** Outcome of an attempt to apply a fix. */
sealed interface FixAttempt {
    /** Done, as one undoable IDE command; [changedFiles] are the paths of the files it changed. */
    data class Applied(val changedFiles: List<String>) : FixAttempt

    /** The file changed since the analysis; nothing was done. */
    data object Stale : FixAttempt
}

/**
 * Gateway boundary: the IDE's own analysis of one file (the passes an editor runs, without opening an editor) and the
 * quick fixes it registers for each problem. Never opens a dialog, an editor or a popup.
 */
interface QuickFixGateway {

    /** Every problem of [path] the IDE reports (weak warnings and above), sorted by position. */
    suspend fun analyse(path: String): ToolResult<AnalysedFile>

    /** The fixes of [problem], in the IDE's order; unavailable ones are left out. */
    suspend fun fixes(problem: AnalysedProblem): ToolResult<List<AnalysedFix>>

    /** Applies [fix] as one undoable command, unless the file changed since it was analysed. */
    suspend fun apply(fix: AnalysedFix): ToolResult<FixAttempt>
}

/** A problem's place for filters: whether it touches any line of [lines]. */
fun AnalysedProblem.touches(lines: IntRange): Boolean = line <= lines.last && endLine >= lines.first

/** Whether a problem of [severity] is reported at [minimum]. */
fun ProblemSeverity.atLeast(minimum: ProblemSeverity): Boolean = minimum == ProblemSeverity.WARNING || this == minimum
