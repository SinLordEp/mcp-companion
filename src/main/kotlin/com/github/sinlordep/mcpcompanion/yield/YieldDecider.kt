package com.github.sinlordep.mcpcompanion.yield

/** Decides each toolset's [ToolsetState] from the foreign tools present and a [YieldSpecification]. */
class YieldDecider(private val foreignTools: ForeignToolGateway, private val specification: YieldSpecification) {

    fun stateOf(toolsetId: String): ToolsetState = statesOf(listOf(toolsetId)).getValue(toolsetId)

    /** States of several toolsets, reading the foreign tools once. */
    fun statesOf(toolsetIds: Collection<String>): Map<String, ToolsetState> {
        val present = foreignTools.foreignToolNames()
        return toolsetIds.associateWith { id ->
            val reasons = specification.reasonsToYield(YieldCandidate(id, present))
            if (reasons.isEmpty()) ToolsetState.Available else ToolsetState.Yielded(reasons)
        }
    }
}
