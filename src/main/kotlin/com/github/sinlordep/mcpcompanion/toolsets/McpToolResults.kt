package com.github.sinlordep.mcpcompanion.toolsets

import com.github.sinlordep.mcpcompanion.common.result.ToolError
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.intellij.mcpserver.mcpFail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The one place where a [ToolResult] becomes MCP output: a success is returned for the server to serialise; a
 * failure ends the call as an MCP error (`isError: true`) with readable text and structured content:
 * `{"error": {"code", "message", "nextStep"}}`.
 */
fun <T> ToolResult<T>.toMcpOutput(): T = when (this) {
    is ToolResult.Success -> value
    is ToolResult.Failure -> mcpFail(error.toText(), error.toJson())
}

internal fun ToolError.toText(): String = buildString {
    append('[').append(code.name).append("] ").append(message)
    nextStep?.let { append(" Next step: ").append(it) }
}

internal fun ToolError.toJson(): JsonObject = buildJsonObject {
    putJsonObject("error") {
        put("code", code.name)
        put("message", message)
        nextStep?.let { put("nextStep", it) }
    }
}
