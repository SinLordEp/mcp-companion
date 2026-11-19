package com.github.sinlordep.mcpcompanion.common.refactoring

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.source.CodeSite
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocation

/** The two actions every refactoring tool has: look first, then change. */
enum class RefactoringAction { PREVIEW, APPLY }

/**
 * One call of a refactoring tool: what to refactor as the client names it ([target]: a symbol, a code range), the
 * refactoring's own [details] (e.g. where to move it), and the [page] of affected files a preview reports.
 */
data class RefactoringRequest<out Q, out D>(val target: Q, val details: D, val page: PageRequest)

/** A file the refactoring changes, with the number of [references] in it that it rewrites (0: only declarations). */
data class AffectedFile(val path: String, val origin: FileOrigin, val references: Int) {
    companion object {
        val ORDER: Comparator<AffectedFile> = compareBy { it.path }
    }
}

/**
 * Something that would break or change meaning if the refactoring ran, as the IDE reports it, e.g. a reference that
 * would no longer be accessible. [site] is null when the IDE names no place.
 */
data class RefactoringConflict(val message: String, val site: CodeSite?) {
    companion object {
        val ORDER: Comparator<RefactoringConflict> =
            compareBy<RefactoringConflict, SymbolLocation?>(nullsLast(SymbolLocation.ORDER)) { it.site?.location }
                .thenBy { it.message }
    }
}

/** Opaque state a gateway needs to carry out an analysed refactoring. Use cases pass it on without looking inside. */
interface RefactoringPlan

/**
 * What a refactoring would do: every affected file, the number of references it rewrites, and the conflicts that
 * speak against it. [summary] says in one line what it creates when the request does not say it all (e.g. the
 * signature of an extracted method); null otherwise. [plan] carries it out.
 */
class RefactoringAnalysis(
    val files: List<AffectedFile>,
    val referenceCount: Int,
    val conflicts: List<RefactoringConflict>,
    val plan: RefactoringPlan,
    val summary: String? = null,
)

/** Outcome of an attempt to carry out a [RefactoringPlan]. */
sealed interface RefactoringAttempt {
    /** Done, as one undoable IDE command; [changedFiles] are the paths of the files it changed, as they are now. */
    data class Applied(val changedFiles: List<String>) : RefactoringAttempt

    /** The code changed after the analysis; nothing was done, and the analysis must be repeated. */
    data object Stale : RefactoringAttempt
}

/** Result of a refactoring tool on [target] (a located symbol, a code range), one shape per action. */
sealed interface RefactoringOutcome<out T> {
    val target: T

    /** See [RefactoringAnalysis.summary]. */
    val summary: String?

    /**
     * [files] is one page of all [fileCount] affected files; [conflicts] lists at most [RefactoringActions.MAX_CONFLICTS]
     * of all [conflictCount] conflicts. The refactoring can be applied when there are none.
     */
    data class Previewed<out T>(
        override val target: T,
        val files: Page<AffectedFile>,
        val fileCount: Int,
        val referenceCount: Int,
        val conflicts: List<RefactoringConflict>,
        val conflictCount: Int,
        override val summary: String? = null,
    ) : RefactoringOutcome<T>

    data class Applied<out T>(
        override val target: T,
        val referenceCount: Int,
        val changedFiles: List<String>,
        override val summary: String? = null,
    ) : RefactoringOutcome<T>
}
