package com.github.sinlordep.mcpcompanion.move

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
 * Where `mc_move` puts a class: [targetPackage] (a qualified package name, created when missing) in [sourceRoot] (a
 * client path of a source root), or in the class's current source root when [sourceRoot] is null.
 */
@ConsistentCopyVisibility
data class ClassDestination private constructor(val targetPackage: String, val sourceRoot: String?) {

    companion object {
        private val PACKAGE = Regex("""[\p{L}_$][\p{L}\p{N}_$]*(\.[\p{L}_$][\p{L}\p{N}_$]*)*""")

        /** Validates client input; blank strings count as absent. */
        fun of(targetPackage: String?, sourceRoot: String?): ToolResult<ClassDestination> {
            val name = targetPackage?.trim().orEmpty()
            return when {
                name.isEmpty() -> ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "targetPackage is missing.",
                    "Pass the qualified name of the package to move the class to, e.g. com.example.model.",
                )

                !PACKAGE.matches(name) -> ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "'$name' is not a qualified package name.",
                    "Pass dot-separated identifiers, e.g. com.example.model.",
                )

                else -> ToolResult.success(ClassDestination(name, sourceRoot?.trim()?.takeIf { it.isNotEmpty() }))
            }
        }
    }
}

/** Use case of `mc_move`: the shared preview and apply actions with the class-move gateway. */
class MoveClass(locators: SymbolLocators, gateway: RefactoringGateway<LocatedSymbol, ClassDestination>) {

    private val actions = RefactoringActions(SymbolTargets(locators), gateway, verb = "moved")

    suspend fun execute(
        action: RefactoringAction,
        request: RefactoringRequest<SymbolQuery, ClassDestination>,
    ): ToolResult<RefactoringOutcome<LocatedSymbol>> = actions.execute(action, request)
}
