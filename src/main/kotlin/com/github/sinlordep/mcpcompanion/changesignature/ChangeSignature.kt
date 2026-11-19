package com.github.sinlordep.mcpcompanion.changesignature

import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringAction
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringActions
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringGateway
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringOutcome
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringRequest
import com.github.sinlordep.mcpcompanion.common.refactoring.SymbolTargets
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocators
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery

/** Use case of `mc_change_signature`: the shared preview and apply actions with the signature-change gateway. */
class ChangeSignature(locators: SymbolLocators, gateway: RefactoringGateway<LocatedSymbol, SignatureChange>) {

    private val actions = RefactoringActions(SymbolTargets(locators), gateway, verb = "changed")

    suspend fun execute(
        action: RefactoringAction,
        request: RefactoringRequest<SymbolQuery, SignatureChange>,
    ): ToolResult<RefactoringOutcome<LocatedSymbol>> = actions.execute(action, request)
}
