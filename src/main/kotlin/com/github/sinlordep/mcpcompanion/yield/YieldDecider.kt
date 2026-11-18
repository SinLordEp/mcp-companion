package com.github.sinlordep.mcpcompanion.yield

/** Decides each toolset's [ToolsetState] from the official tools present and a [YieldSpecification]. */
class YieldDecider(private val officialTools: OfficialToolGateway, private val specification: YieldSpecification) {

    fun stateOf(toolsetId: String): ToolsetState = statesOf(listOf(toolsetId)).getValue(toolsetId)

    /** States of several toolsets, reading the official tools once. */
    fun statesOf(toolsetIds: Collection<String>): Map<String, ToolsetState> {
        val present = officialTools.officialToolNames()
        return toolsetIds.associateWith { id ->
            val reasons = specification.reasonsToYield(YieldCandidate(id, present))
            if (reasons.isEmpty()) ToolsetState.Available else ToolsetState.Yielded(reasons)
        }
    }
}
