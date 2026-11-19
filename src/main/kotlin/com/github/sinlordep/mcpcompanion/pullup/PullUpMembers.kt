package com.github.sinlordep.mcpcompanion.pullup

import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringAction
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringActions
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringGateway
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringOutcome
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringRequest
import com.github.sinlordep.mcpcompanion.common.refactoring.SymbolTargets
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocators
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery

/**
 * Where `mc_pull_up` moves a member and what goes with it: [targetClass] is the qualified or simple name of a
 * superclass or interface of the member's class (null: its superclass); [withMembers] names further members of the
 * same class to move along (e.g. a field the method uses); [makeAbstract] leaves the methods' bodies in the subclass
 * and declares them abstract in the target.
 */
@ConsistentCopyVisibility
data class MemberPullUp private constructor(
    val targetClass: String?,
    val withMembers: List<String>,
    val makeAbstract: Boolean,
) {
    companion object {
        /** Validates client input; blank names count as absent, duplicates are dropped. */
        fun of(targetClass: String?, withMembers: List<String>?, makeAbstract: Boolean?): ToolResult<MemberPullUp> {
            val names = withMembers.orEmpty().map { it.trim() }
            return if (names.any { it.isEmpty() || it.any(Char::isWhitespace) }) {
                ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "withMembers holds an empty or malformed name.",
                    "Pass the simple names of fields, methods or nested classes of the same class, e.g. [\"total\"].",
                )
            } else {
                val target = targetClass?.trim()?.takeIf { it.isNotEmpty() }
                ToolResult.success(MemberPullUp(target, names.distinct(), makeAbstract == true))
            }
        }
    }
}

/** Use case of `mc_pull_up`: the shared preview and apply actions with the pull-up gateway. */
class PullUpMembers(locators: SymbolLocators, gateway: RefactoringGateway<LocatedSymbol, MemberPullUp>) {

    private val actions = RefactoringActions(SymbolTargets(locators), gateway, verb = "pulled up")

    suspend fun execute(
        action: RefactoringAction,
        request: RefactoringRequest<SymbolQuery, MemberPullUp>,
    ): ToolResult<RefactoringOutcome<LocatedSymbol>> = actions.execute(action, request)
}
