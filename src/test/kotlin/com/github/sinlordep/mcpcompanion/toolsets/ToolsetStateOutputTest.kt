package com.github.sinlordep.mcpcompanion.toolsets

import com.github.sinlordep.mcpcompanion.yield.ToolsetState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolsetStateOutputTest {

    @Test
    fun `yielded toolset lists the official tools sorted and serialises as lower-case state`() {
        val overview = ToolsetOverview("nav", "Navigation", "Nav", "d", ToolsetState.Yielded(setOf("z_tool", "a_tool")))
        val output = ToolsetStateOutput.from(overview)
        assertEquals(
            """{"id":"nav","name":"Navigation","state":"yielded","officialTools":["a_tool","z_tool"]}""",
            Json.encodeToString(ToolsetStateOutput.serializer(), output),
        )
    }

    @Test
    fun `active toolset has no official tools`() {
        val output = ToolsetStateOutput.from(ToolsetOverview("nav", "Navigation", "Nav", "d", ToolsetState.Available))
        assertEquals(
            ToolsetStateOutput("nav", "Navigation", ToolsetStateOutput.StateName.AVAILABLE, emptyList()),
            output,
        )
    }
}
