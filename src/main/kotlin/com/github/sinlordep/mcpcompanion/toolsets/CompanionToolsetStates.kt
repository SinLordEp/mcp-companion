package com.github.sinlordep.mcpcompanion.toolsets

import com.github.sinlordep.mcpcompanion.yield.EquivalenceCatalog
import com.github.sinlordep.mcpcompanion.yield.ExtensionPointForeignToolGateway
import com.github.sinlordep.mcpcompanion.yield.OfficialEquivalentPresent
import com.github.sinlordep.mcpcompanion.yield.ToolsetState
import com.github.sinlordep.mcpcompanion.yield.YieldDecider
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * Application-level composition root of stepping aside: wires the yield decision for our toolsets and answers
 * "which of our toolsets have stepped aside".
 */
@Service(Service.Level.APP)
class CompanionToolsetStates internal constructor(private val decider: YieldDecider) {

    constructor() : this(
        YieldDecider(
            foreignTools = ExtensionPointForeignToolGateway { it is CompanionToolset },
            specification = OfficialEquivalentPresent(EquivalenceCatalog.bundled()),
        ),
    )

    fun stateOf(toolsetId: String): ToolsetState = decider.stateOf(toolsetId)

    /** Every installed toolset of ours with its current state, in registration order. */
    fun overview(): List<ToolsetOverview> {
        val toolsets = McpToolset.EP.extensionList.filterIsInstance<CompanionToolset>()
        val states = decider.statesOf(toolsets.map { it.toolsetId })
        return toolsets.map { toolset ->
            ToolsetOverview(
                id = toolset.toolsetId,
                displayName = toolset.displayName(),
                description = toolset.displayDescription(),
                state = states.getValue(toolset.toolsetId),
            )
        }
    }

    companion object {
        fun getInstance(): CompanionToolsetStates = service()
    }
}

/** One of our toolsets as shown in `mc_status` and on the settings page. */
data class ToolsetOverview(val id: String, val displayName: String, val description: String, val state: ToolsetState)
