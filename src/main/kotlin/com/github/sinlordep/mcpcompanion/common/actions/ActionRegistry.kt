package com.github.sinlordep.mcpcompanion.common.actions

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** One `action` of a multi-action tool (command pattern, docs/PLAN.md section 5.1). */
fun interface ActionHandler<in R, out O> {
    suspend fun handle(request: R): ToolResult<O>
}

/**
 * Dispatches a tool's `action` to its [ActionHandler]. Adding an action means adding a handler to the map, not
 * changing a `when` over all actions.
 */
class ActionRegistry<K, in R, out O>(private val handlers: Map<K, ActionHandler<R, O>>) {

    suspend fun dispatch(action: K, request: R): ToolResult<O> = handlers[action]?.handle(request)
        ?: ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "Unknown action '$action'.",
            "Use one of: ${handlers.keys.joinToString()}.",
        )
}

/** The raw arguments of one call of a multi-action tool; [given] are the optional parameters the client set. */
interface ToolArguments<P> {
    fun given(): Set<P>
}

/** An action that accepts only [parameters]; any other argument is rejected before [handle] (see [ParameterGuard]). */
interface ParameterizedAction<P, in R : ToolArguments<P>, out O> : ActionHandler<R, O> {
    /** The action as clients write it, e.g. `wait_ready`. */
    val actionName: String
    val parameters: Set<P>
}

/**
 * Rejects arguments that do not apply to the action, so that a mistyped call fails instead of being half-obeyed.
 * [label] is the parameter's name as clients write it.
 */
class ParameterGuard<P, in R : ToolArguments<P>, out O>(
    private val action: ParameterizedAction<P, R, O>,
    private val label: (P) -> String,
) : ActionHandler<R, O> {

    override suspend fun handle(request: R): ToolResult<O> {
        val unexpected = request.given() - action.parameters
        if (unexpected.isEmpty()) return action.handle(request)
        val name = action.actionName
        val accepted = action.parameters.joinToString(transform = label).ifEmpty { "none" }
        return ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "action=$name does not take ${unexpected.joinToString(transform = label)}.",
            "Parameters of action=$name: $accepted.",
        )
    }
}
