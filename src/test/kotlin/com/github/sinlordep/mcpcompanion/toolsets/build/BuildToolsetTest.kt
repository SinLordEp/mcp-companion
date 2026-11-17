package com.github.sinlordep.mcpcompanion.toolsets.build

import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `mc_build` through the real gateways, without building: the light test project cannot run a real build, so builds
 * themselves are checked end to end (docs/notes-step7.md). Here: the builder is registered, status, refusals.
 */
class BuildToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<BuildToolset>().single()

    override fun setUp() {
        super.setUp()
        tools = ToolCalls(project)
    }

    override fun tearDown() {
        try {
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    fun testStatusIsIdleBeforeAnyBuild() {
        val output = tools.call(TOOL) { toolset.build(BuildActionInput.STATUS) }
        assertEquals("idle", output.state)
        assertNull(output.build)
        assertNull(output.running)
    }

    fun testUnknownModulesAndMisplacedArgumentsAreRefused() {
        assertEquals("NOT_FOUND", tools.errorCode(TOOL) { toolset.build(BuildActionInput.BUILD, module = "nope") })
        assertEquals("INVALID_ARGUMENT", tools.errorCode(TOOL) { toolset.build(BuildActionInput.BUILD) })
        assertEquals(
            "INVALID_ARGUMENT",
            tools.errorCode(TOOL) { toolset.build(BuildActionInput.STATUS, module = module.name) },
        )
    }

    fun testServerCallWithJsonArguments() {
        val result = tools.callJson(TOOL, JsonObject(mapOf("action" to JsonPrimitive("status"))))
        assertFalse(result.toString(), result.isError)
        val json = Json.parseToJsonElement(
            result.content.filterIsInstance<McpToolCallResultContent.Text>().single().text,
        ).jsonObject
        assertEquals("idle", json.getValue("state").jsonPrimitive.content)
    }

    private companion object {
        const val TOOL = "mc_build"
    }
}
