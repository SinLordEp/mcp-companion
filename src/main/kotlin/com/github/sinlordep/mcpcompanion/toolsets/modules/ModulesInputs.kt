package com.github.sinlordep.mcpcompanion.toolsets.modules

import com.github.sinlordep.mcpcompanion.modules.GraphDirection
import com.github.sinlordep.mcpcompanion.modules.ModulesAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `action`. */
@Serializable
enum class ModulesActionInput(val action: ModulesAction) {
    @SerialName("list")
    LIST(ModulesAction.LIST),

    @SerialName("graph")
    GRAPH(ModulesAction.GRAPH),

    @SerialName("cycles")
    CYCLES(ModulesAction.CYCLES),
}

/** MCP input values of `direction`. */
@Serializable
enum class DirectionInput(val direction: GraphDirection) {
    @SerialName("dependencies")
    DEPENDENCIES(GraphDirection.DEPENDENCIES),

    @SerialName("dependents")
    DEPENDENTS(GraphDirection.DEPENDENTS),
}
