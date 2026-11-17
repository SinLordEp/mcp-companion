package com.github.sinlordep.mcpcompanion.safedelete

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.source.CodeSite
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocation
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery

enum class SafeDeleteAction { PREVIEW, APPLY }

/** [page] cuts the list of blockers a preview reports. */
data class SafeDeleteRequest(val symbol: SymbolQuery, val page: PageRequest)

/** Why a symbol cannot be deleted safely. */
enum class BlockerKind {
    /** Code that uses the symbol and would break. */
    REFERENCE,

    /** A declaration that depends on the symbol and would be changed or deleted with it, e.g. an overriding method. */
    DEPENDENT,

    /** A conflict the language reports, e.g. a declaration that cannot be removed in this context. */
    CONFLICT,
}

/** One reason against deleting. [site] is null when the language reports no place; [message] only for conflicts. */
data class Blocker(val kind: BlockerKind, val site: CodeSite?, val message: String?) {
    companion object {
        /** Reading order, so that preview pages are stable (the platform finds usages in no fixed order). */
        val ORDER: Comparator<Blocker> =
            compareBy<Blocker, SymbolLocation?>(nullsLast(SymbolLocation.ORDER)) {
                it.site?.location
            }.thenBy { it.kind }
    }
}

/** Opaque state that a gateway needs to carry out an analysed deletion. Use cases pass it on without looking inside. */
interface DeletionPlan

/**
 * What deleting a symbol would do. [cleanups] counts usages the deletion removes along with the symbol, such as
 * imports of it, which are no reason against deleting. [alsoDeleted] are unused declarations that only exist to
 * override or implement the symbol and go with it.
 */
class SafeDeleteAnalysis(
    val blockers: List<Blocker>,
    val cleanups: Int,
    val alsoDeleted: List<LocatedSymbol>,
    val plan: DeletionPlan,
)

/** Outcome of an attempt to carry out a [DeletionPlan]. */
sealed interface DeletionAttempt {
    data object Deleted : DeletionAttempt

    /** The code changed after the analysis; nothing was deleted, and the analysis must be repeated. */
    data object Stale : DeletionAttempt
}

/** Result of `mc_safe_delete`, one shape per action. */
sealed interface SafeDeleteOutcome {
    val target: LocatedSymbol

    /** [blockers] is one page of all [blockerCount] blockers; the symbol can be deleted when there are none. */
    data class Previewed(
        override val target: LocatedSymbol,
        val blockers: Page<Blocker>,
        val blockerCount: Int,
        val cleanups: Int,
        val alsoDeleted: List<LocatedSymbol>,
    ) : SafeDeleteOutcome

    data class Deleted(override val target: LocatedSymbol, val cleanups: Int, val alsoDeleted: List<LocatedSymbol>) :
        SafeDeleteOutcome
}
