package com.github.sinlordep.mcpcompanion.toolsets.refactoring

import com.github.sinlordep.mcpcompanion.common.refactoring.AffectedFile
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringAction
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringConflict
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringOutcome
import com.github.sinlordep.mcpcompanion.common.source.SourceRange
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.toolsets.symbols.SymbolOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `action`, shared by the refactoring tools. */
@Serializable
enum class RefactoringActionInput(val action: RefactoringAction) {
    @SerialName("preview")
    PREVIEW(RefactoringAction.PREVIEW),

    @SerialName("apply")
    APPLY(RefactoringAction.APPLY),
}

/**
 * MCP output model of the refactoring tools (`mc_move`, `mc_change_signature`, `mc_pull_up`, `mc_extract_method`).
 * The target is [symbol] for tools that refactor a declaration and [range] for those that refactor code given by its
 * positions; [summary] says what the refactoring creates when the request does not say it all (the extracted
 * method's signature). After `preview`, [applied] is false, [files] is one page of all [fileCount] affected files and
 * [conflicts] lists up to 50 of [conflictCount]. After a successful `apply`, [applied] is true, [changedFiles] lists
 * the files the IDE changed (paths as they are now) and there are no conflicts.
 */
@Serializable
data class RefactoringOutput(
    val symbol: SymbolOutput? = null,
    val range: SourceRangeOutput? = null,
    val summary: String? = null,
    val canApply: Boolean,
    val applied: Boolean,
    val fileCount: Int,
    val referenceCount: Int,
    val files: List<AffectedFileOutput>,
    val conflictCount: Int,
    val conflicts: List<ConflictOutput>,
    val changedFiles: List<String>,
    val hasMore: Boolean,
    val nextOffset: Int? = null,
) {
    companion object {
        /** The output of a tool that refactors a declaration. */
        fun ofSymbol(outcome: RefactoringOutcome<LocatedSymbol>): RefactoringOutput =
            of(outcome, symbol = SymbolOutput.from(outcome.target))

        /** The output of a tool that refactors code given by its positions. */
        fun ofRange(outcome: RefactoringOutcome<SourceRange>): RefactoringOutput =
            of(outcome, range = SourceRangeOutput.from(outcome.target))

        private fun of(
            outcome: RefactoringOutcome<*>,
            symbol: SymbolOutput? = null,
            range: SourceRangeOutput? = null,
        ): RefactoringOutput = when (outcome) {
            is RefactoringOutcome.Previewed -> RefactoringOutput(
                symbol = symbol,
                range = range,
                summary = outcome.summary,
                canApply = outcome.conflictCount == 0,
                applied = false,
                fileCount = outcome.fileCount,
                referenceCount = outcome.referenceCount,
                files = outcome.files.items.map(AffectedFileOutput::from),
                conflictCount = outcome.conflictCount,
                conflicts = outcome.conflicts.map(ConflictOutput::from),
                changedFiles = emptyList(),
                hasMore = outcome.files.hasMore,
                nextOffset = outcome.files.nextOffset,
            )

            is RefactoringOutcome.Applied -> RefactoringOutput(
                symbol = symbol,
                range = range,
                summary = outcome.summary,
                canApply = true,
                applied = true,
                fileCount = outcome.changedFiles.size,
                referenceCount = outcome.referenceCount,
                files = emptyList(),
                conflictCount = 0,
                conflicts = emptyList(),
                changedFiles = outcome.changedFiles,
                hasMore = false,
            )
        }
    }
}

/** The code a refactoring was asked to change, as the client gave it; [endColumn] is absent for "to the line end". */
@Serializable
data class SourceRangeOutput(
    val path: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int? = null,
) {
    companion object {
        fun from(range: SourceRange) =
            SourceRangeOutput(range.path, range.startLine, range.startColumn, range.endLine, range.endColumn)
    }
}

/** A file the refactoring changes; [references] is the number of rewritten references in it. */
@Serializable
data class AffectedFileOutput(val path: String, val references: Int) {
    companion object {
        fun from(file: AffectedFile) = AffectedFileOutput(file.path, file.references)
    }
}

/** A conflict; location fields are absent when the IDE names no place. */
@Serializable
data class ConflictOutput(
    val message: String,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val lineText: String? = null,
    val enclosing: String? = null,
) {
    companion object {
        fun from(conflict: RefactoringConflict) = ConflictOutput(
            message = conflict.message,
            path = conflict.site?.location?.path,
            line = conflict.site?.location?.line,
            column = conflict.site?.location?.column,
            lineText = conflict.site?.lineText,
            enclosing = conflict.site?.enclosingDeclaration,
        )
    }
}
