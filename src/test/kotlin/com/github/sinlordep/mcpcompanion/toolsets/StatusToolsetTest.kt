package com.github.sinlordep.mcpcompanion.toolsets

import com.github.sinlordep.mcpcompanion.testing.withMcpCallContext
import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StatusToolsetTest : BasePlatformTestCase() {

    private fun registeredStatusToolset(): StatusToolset =
        McpToolset.EP.extensionList.filterIsInstance<StatusToolset>().single()

    private fun servedMcStatus(): McpTool =
        McpServerService.getInstance().getMcpTools().single { it.descriptor.name == "mc_status" }

    fun testToolsetFollowsTheCompanionContract() {
        val toolset = registeredStatusToolset()
        assertEquals("status", toolset.toolsetId)
        assertFalse(toolset.isExperimental())
        assertEquals("MCP Companion: Status", toolset.displayName())
        assertTrue(toolset.isEnabled())
    }

    fun testReportsVersionBuildProjectAndToolsetStates() = timeoutRunBlocking {
        val output = withMcpCallContext(project, servedMcStatus().descriptor) { registeredStatusToolset().status() }
        assertEquals(ApplicationInfo.getInstance().build.asString(), output.ideBuild)
        assertFalse("plugin version must come from the descriptor", output.pluginVersion.isNullOrBlank())
        assertEquals(project.name, output.projectName)
        assertEquals(
            ToolsetStateOutput("status", "MCP Companion: Status", ToolsetStateOutput.StateName.AVAILABLE, emptyList()),
            output.toolsets.first(),
        )
        assertEquals(
            listOf(
                "status",
                "find_usages",
                "find_implementations",
                "type_hierarchy",
                "safe_delete",
                "move",
                "change_signature",
                "run",
                "modules",
                "sync",
                "diagnostics",
                "build",
                "quick_fix",
                "file_structure",
                "local_history",
                "vcs_history",
            ),
            output.toolsets.map { it.id },
        )
        assertTrue(output.toolsets.all { it.state == ToolsetStateOutput.StateName.AVAILABLE })
    }

    /** Calls the tool the way the MCP server does: through the McpTool built by the server, with JSON in and out. */
    fun testServerCallsMcStatusAndSerialisesTheOutput() = timeoutRunBlocking {
        val tool = servedMcStatus()
        val result = withMcpCallContext(project, tool.descriptor) { tool.call(JsonObject(emptyMap())) }

        assertFalse(result.toString(), result.isError)
        val text = result.content.filterIsInstance<McpToolCallResultContent.Text>().single().text
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals(ApplicationInfo.getInstance().build.asString(), json.getValue("ideBuild").jsonPrimitive.content)
        assertEquals(project.name, json.getValue("projectName").jsonPrimitive.content)
        val toolset = json.getValue("toolsets").jsonArray.first().jsonObject
        assertEquals("status", toolset.getValue("id").jsonPrimitive.content)
        assertEquals("available", toolset.getValue("state").jsonPrimitive.content)
        assertTrue(toolset.getValue("officialTools").jsonArray.isEmpty())
    }
}
