package com.github.sinlordep.mcpcompanion.quickfix

import com.github.sinlordep.mcpcompanion.common.paging.Page
import java.util.zip.CRC32

/**
 * Identifier of one fix of one problem in one content of a file: `<line>:<column>/<position>-<check>`, e.g.
 * `12:9/2-3fa1b2c4` or `12:9/2.1-…` for a choice inside a fix. [check] is a checksum of the file's content stamp, the
 * problem (range and message), the fix's position and name, so an id is reproduced by every analysis of the same
 * content and matches nothing once the file changed.
 */
@JvmInline
value class FixId(val value: String) {

    /** The problem's start line and column, as the id names them. */
    val line: Int get() = value.substringBefore(':').toInt()
    val column: Int get() = value.substringAfter(':').substringBefore('/').toInt()

    override fun toString(): String = value

    companion object {
        private val SYNTAX = Regex("[1-9][0-9]{0,8}:[1-9][0-9]{0,8}/[1-9][0-9]{0,3}(\\.[1-9][0-9]{0,3})?-[0-9a-f]{8}")

        /** Null when [text] is not an id this tool hands out. */
        fun parse(text: String): FixId? = text.trim().takeIf(SYNTAX::matches)?.let(::FixId)

        fun of(file: AnalysedFile, problem: AnalysedProblem, fix: AnalysedFix): FixId {
            val checksum = CRC32()
            listOf(
                file.contentStamp,
                problem.startOffset.toString(),
                problem.endOffset.toString(),
                problem.message,
                fix.position,
                fix.name,
            ).forEach { checksum.update((it + '\u0000').toByteArray()) }
            return FixId("${problem.line}:${problem.column}/${fix.position}-${"%08x".format(checksum.value)}")
        }
    }
}

/** A fix as `list` reports it: [refusal] null means `apply` can carry it out. */
data class FixOption(val id: FixId, val name: String, val refusal: String?)

/** A problem with the fixes the IDE offers for it (possibly none). */
data class ProblemFixes(val problem: AnalysedProblem, val fixes: List<FixOption>)

/** Result of `mc_quick_fix`, one shape per action. */
sealed interface QuickFixOutcome {
    val path: String

    /**
     * [problems] is one page of the [problemCount] problems in the requested lines at the requested severity, in
     * file order.
     */
    data class Listed(override val path: String, val problems: Page<ProblemFixes>, val problemCount: Int) :
        QuickFixOutcome

    /** [fix] was applied to [problem]; [changedFiles] are the files it changed, as they are now. */
    data class Applied(
        override val path: String,
        val problem: AnalysedProblem,
        val fix: FixOption,
        val changedFiles: List<String>,
    ) : QuickFixOutcome
}
