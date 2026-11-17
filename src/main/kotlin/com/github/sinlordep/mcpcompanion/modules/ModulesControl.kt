package com.github.sinlordep.mcpcompanion.modules

import com.github.sinlordep.mcpcompanion.common.actions.ActionRegistry
import com.github.sinlordep.mcpcompanion.common.actions.ParameterGuard
import com.github.sinlordep.mcpcompanion.common.actions.ParameterizedAction
import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** One `mc_modules` action; arguments it does not take are rejected before [handle]. */
interface ModulesActionHandler : ParameterizedAction<ModulesParameter, ModulesArguments, ModulesOutcome> {
    val action: ModulesAction

    override val actionName: String get() = action.name.lowercase()
}

/** Use case of `mc_modules`: dispatches each action to its handler (command pattern) after checking the arguments. */
class ModulesControl(handlers: List<ModulesActionHandler>) {

    private val actions =
        ActionRegistry(handlers.associate { it.action to ParameterGuard(it, ModulesParameter::label) })

    suspend fun execute(action: ModulesAction, arguments: ModulesArguments): ToolResult<ModulesOutcome> =
        actions.dispatch(action, arguments)

    companion object {
        fun create(model: ProjectModelGateway): ModulesControl =
            ModulesControl(listOf(ListModules(model), GraphModules(model), FindCycles(model)))
    }
}

internal fun ModulesArguments.pageRequest(): ToolResult<PageRequest> = PageRequest.of(limit, offset)
