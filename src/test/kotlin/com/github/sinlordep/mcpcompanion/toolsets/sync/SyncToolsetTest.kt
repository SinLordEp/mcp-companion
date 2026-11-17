package com.github.sinlordep.mcpcompanion.toolsets.sync

import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.externalSystem.ExternalSystemManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert

/**
 * `mc_sync` in a project without linked build projects, through the real gateways: every installed build system is
 * listed, none has projects, and `sync` has nothing to do. Real syncs are checked end to end (docs/notes-step4.md).
 */
class SyncToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<SyncToolset>().single()

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

    fun testStatusListsEveryBuildSystemOfTheIdeWithoutProjects() {
        val output = tools.call(TOOL) { toolset.sync(SyncActionInput.STATUS) }
        val ids = output.buildSystems!!.map { it.id }
        val externalSystems = ExternalSystemManager.EP_NAME.extensionList.map { it.systemId.id.lowercase() }
        assertEquals((externalSystems + "maven").sorted(), ids)
        assertTrue(output.buildSystems.all { it.projects.isEmpty() })
        assertEquals("Maven", output.buildSystems.single { it.id == "maven" }.name)
    }

    fun testSyncWithoutLinkedProjectsIsNotFoundAndUnknownBuildSystemsAreNamed() {
        assertEquals("NOT_FOUND", tools.errorCode(TOOL) { toolset.sync(SyncActionInput.SYNC) })
        assertEquals("NOT_FOUND", tools.errorCode(TOOL) { toolset.sync(SyncActionInput.SYNC, buildSystem = "bazel") })
    }

    fun testServerCallWithJsonArgumentsAndARejectedParameter() {
        val result = tools.callJson(TOOL, JsonObject(mapOf("action" to JsonPrimitive("status"))))
        assertFalse(result.toString(), result.isError)
        val json = Json.parseToJsonElement(
            result.content.filterIsInstance<McpToolCallResultContent.Text>().single().text,
        )
        val maven = json.jsonObject.getValue("buildSystems").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "maven" }
        assertEquals(0, maven.getValue("projects").jsonArray.size)

        val rejected = Assert.assertThrows(McpExpectedError::class.java) {
            tools.callJson(
                TOOL,
                JsonObject(mapOf("action" to JsonPrimitive("status"), "timeoutSeconds" to JsonPrimitive(5))),
            )
        }
        assertTrue(rejected.message, "does not take timeoutSeconds" in rejected.message!!)
    }

    private companion object {
        const val TOOL = "mc_sync"
    }
}
