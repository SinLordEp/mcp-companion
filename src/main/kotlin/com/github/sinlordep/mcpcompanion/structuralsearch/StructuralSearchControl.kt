package com.github.sinlordep.mcpcompanion.structuralsearch

import com.github.sinlordep.mcpcompanion.common.actions.ActionRegistry
import com.github.sinlordep.mcpcompanion.common.actions.ParameterGuard
import com.github.sinlordep.mcpcompanion.common.actions.ParameterizedAction
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** One `mc_structural_search` action; arguments outside [parameters] are rejected before [handle]. */
interface StructuralSearchActionHandler :
    ParameterizedAction<StructuralSearchParameter, StructuralSearchArguments, StructuralSearchOutcome> {
    val action: StructuralSearchAction

    override val actionName: String get() = action.name.lowercase()
}

/** What an `mc_structural_search` action answers. */
sealed interface StructuralSearchOutcome {
    /** `search`: the matches of [source]. */
    data class Matches(val source: PatternSource, val found: FoundMatches) : StructuralSearchOutcome

    /** `templates`: the IDE's search templates that pass the filters. */
    data class Templates(val found: FoundTemplates) : StructuralSearchOutcome
}

/** Use case of `mc_structural_search`: dispatches each action to its handler after checking the arguments. */
class StructuralSearchControl(gateway: StructuralSearchGateway) {

    private val actions = ActionRegistry(
        listOf(SearchStructurally(gateway), ListSearchTemplates(gateway))
            .associate { it.action to ParameterGuard(it, StructuralSearchParameter::label) },
    )

    suspend fun execute(
        action: StructuralSearchAction,
        arguments: StructuralSearchArguments,
    ): ToolResult<StructuralSearchOutcome> = actions.dispatch(action, arguments)
}
