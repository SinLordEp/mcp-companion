package com.github.sinlordep.mcpcompanion.yield

import org.junit.Assert.assertEquals
import org.junit.Test

class YieldDeciderTest {

    private val catalog = EquivalenceCatalog(mapOf("nav" to setOf("find_usages"), "run" to setOf("run_anything")))

    private class CountingGateway(private val names: Set<String>) : OfficialToolGateway {
        var calls = 0
        override fun officialToolNames(): Set<String> = names.also { calls++ }
    }

    @Test
    fun `toolset yields to the present official equivalent`() {
        val decider =
            YieldDecider(CountingGateway(setOf("find_usages", "read_file")), OfficialEquivalentPresent(catalog))
        assertEquals(ToolsetState.Yielded(setOf("find_usages")), decider.stateOf("nav"))
        assertEquals(ToolsetState.Available, decider.stateOf("run"))
        assertEquals(ToolsetState.Available, decider.stateOf("status"))
    }

    @Test
    fun `states of several toolsets read the official tools once`() {
        val gateway = CountingGateway(setOf("run_anything"))
        val states = YieldDecider(gateway, OfficialEquivalentPresent(catalog)).statesOf(listOf("nav", "run"))
        assertEquals(
            mapOf("nav" to ToolsetState.Available, "run" to ToolsetState.Yielded(setOf("run_anything"))),
            states,
        )
        assertEquals(1, gateway.calls)
    }

    @Test
    fun `official tools are re-read on every decision`() {
        var present = emptySet<String>()
        val decider = YieldDecider({ present }, OfficialEquivalentPresent(catalog))
        assertEquals(ToolsetState.Available, decider.stateOf("nav"))
        present = setOf("find_usages")
        assertEquals(ToolsetState.Yielded(setOf("find_usages")), decider.stateOf("nav"))
    }
}
