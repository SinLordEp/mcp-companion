package com.github.sinlordep.mcpcompanion.toolsets.safedelete

import com.github.sinlordep.mcpcompanion.safedelete.Blocker
import com.github.sinlordep.mcpcompanion.safedelete.BlockerKind
import com.github.sinlordep.mcpcompanion.safedelete.SafeDeleteAction
import com.github.sinlordep.mcpcompanion.safedelete.SafeDeleteOutcome
import com.github.sinlordep.mcpcompanion.toolsets.symbols.OriginOutput
import com.github.sinlordep.mcpcompanion.toolsets.symbols.SymbolOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `action`. */
@Serializable
enum class SafeDeleteActionInput(val action: SafeDeleteAction) {
    @SerialName("preview")
    PREVIEW(SafeDeleteAction.PREVIEW),

    @SerialName("apply")
    APPLY(SafeDeleteAction.APPLY),
}

/**
 * MCP output model of `mc_safe_delete`. After `preview`, [deleted] is false and [blockers] is one page of all
 * [blockerCount] blockers. After a successful `apply`, [deleted] is true and there are no blockers.
 */
@Serializable
data class SafeDeleteOutput(
    val symbol: SymbolOutput,
    val canDelete: Boolean,
    val deleted: Boolean,
    val blockerCount: Int,
    val blockers: List<BlockerOutput>,
    val cleanups: Int,
    val alsoDeleted: List<SymbolOutput>,
    val hasMore: Boolean,
    val nextOffset: Int? = null,
) {
    companion object {
        fun from(outcome: SafeDeleteOutcome): SafeDeleteOutput = when (outcome) {
            is SafeDeleteOutcome.Previewed -> SafeDeleteOutput(
                symbol = SymbolOutput.from(outcome.target),
                canDelete = outcome.blockerCount == 0,
                deleted = false,
                blockerCount = outcome.blockerCount,
                blockers = outcome.blockers.items.map(BlockerOutput::from),
                cleanups = outcome.cleanups,
                alsoDeleted = outcome.alsoDeleted.map(SymbolOutput::from),
                hasMore = outcome.blockers.hasMore,
                nextOffset = outcome.blockers.nextOffset,
            )

            is SafeDeleteOutcome.Deleted -> SafeDeleteOutput(
                symbol = SymbolOutput.from(outcome.target),
                canDelete = true,
                deleted = true,
                blockerCount = 0,
                blockers = emptyList(),
                cleanups = outcome.cleanups,
                alsoDeleted = outcome.alsoDeleted.map(SymbolOutput::from),
                hasMore = false,
            )
        }
    }
}

/** One reason against deleting. Location fields are absent when the language reports no place. */
@Serializable
data class BlockerOutput(
    val kind: KindOutput,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val lineText: String? = null,
    val enclosing: String? = null,
    val message: String? = null,
    val origin: OriginOutput? = null,
) {
    @Serializable
    enum class KindOutput {
        @SerialName("reference")
        REFERENCE,

        @SerialName("dependent")
        DEPENDENT,

        @SerialName("conflict")
        CONFLICT,
    }

    companion object {
        fun from(blocker: Blocker): BlockerOutput = BlockerOutput(
            kind = when (blocker.kind) {
                BlockerKind.REFERENCE -> KindOutput.REFERENCE
                BlockerKind.DEPENDENT -> KindOutput.DEPENDENT
                BlockerKind.CONFLICT -> KindOutput.CONFLICT
            },
            path = blocker.site?.location?.path,
            line = blocker.site?.location?.line,
            column = blocker.site?.location?.column,
            lineText = blocker.site?.lineText,
            enclosing = blocker.site?.enclosingDeclaration,
            message = blocker.message,
            origin = blocker.site?.location?.origin?.let(OriginOutput::of),
        )
    }
}
