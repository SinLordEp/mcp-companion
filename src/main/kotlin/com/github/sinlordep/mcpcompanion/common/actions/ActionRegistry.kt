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
