package com.github.sinlordep.mcpcompanion.toolsets

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.intellij.mcpserver.McpExpectedError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpToolResultsTest {

    @Test
    fun `success is returned as the tool output`() {
        assertEquals("value", ToolResult.success("value").toMcpOutput())
    }

    @Test
    fun `failure becomes an MCP error with text and structured content`() {
        val result: ToolResult<String> =
            ToolResult.failure(ErrorCode.NOT_FOUND, "No Java class named a.B.", "Check the package.")
        val error = assertThrows(McpExpectedError::class.java) { result.toMcpOutput() }

        assertEquals("[NOT_FOUND] No Java class named a.B. Next step: Check the package.", error.mcpErrorText)
        assertEquals(
            Json.parseToJsonElement(
                """
                {"error": {"code": "NOT_FOUND", "message": "No Java class named a.B.", "nextStep": "Check the package."}}
                """.trimIndent(),
            ),
            error.mcpErrorStructureContent,
        )
    }

    @Test
    fun `a refusal by the user is DENIED`() {
        val error = assertThrows(McpExpectedError::class.java) {
            ToolResult.failure(ErrorCode.DENIED, "The user denied it.", "Do not retry.").toMcpOutput()
        }
        assertEquals("[DENIED] The user denied it. Next step: Do not retry.", error.mcpErrorText)
        val details = (error.mcpErrorStructureContent as JsonObject).getValue("error") as JsonObject
        assertEquals("\"DENIED\"", details.getValue("code").toString())
    }

    @Test
    fun `failure without next step omits it`() {
        val error = assertThrows(McpExpectedError::class.java) {
            ToolResult.failure(ErrorCode.UNSUPPORTED, "Not here.").toMcpOutput()
        }
        assertEquals("[UNSUPPORTED] Not here.", error.mcpErrorText)
        val details = (error.mcpErrorStructureContent as JsonObject).getValue("error") as JsonObject
        assertEquals(setOf("code", "message"), details.keys)
    }
}
