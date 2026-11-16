package com.github.sinlordep.mcpcompanion.testing

import com.intellij.mcpserver.ClientInfo
import com.intellij.mcpserver.McpCallAdditionalDataElement
import com.intellij.mcpserver.McpCallHeaders
import com.intellij.mcpserver.McpCallInfo
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolFilter
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.project.Project
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * Runs [block] inside the coroutine context the MCP server installs for a call of [tool], bound to [project].
 *
 * Tools read the project through `currentCoroutineContext().project`, which throws outside an MCP call.
 * `McpCallInfo` has no public constructor in 2026.3, so this test-only helper builds it reflectively and fills
 * every required parameter by type; optional parameters keep their defaults.
 */
suspend fun <T> withMcpCallContext(project: Project, tool: McpToolDescriptor, block: suspend () -> T): T {
    val constructor = checkNotNull(McpCallInfo::class.primaryConstructor) { "McpCallInfo has no primary constructor" }
    constructor.isAccessible = true
    val values = argumentsByType(project, tool)
    val arguments = constructor.parameters
        .filterNot { it.isOptional }
        .associateWith { parameter -> argumentFor(parameter, values) }
    val callInfo = constructor.callBy(arguments)
    return withContext(McpCallAdditionalDataElement(callInfo)) { block() }
}

private fun argumentsByType(project: Project, tool: McpToolDescriptor): Map<KClass<*>, Any> = mapOf(
    Project::class to project,
    ClientInfo::class to ClientInfo("mcp-companion-tests", "0"),
    McpToolDescriptor::class to tool,
    McpServerService.McpSessionOptions::class to
        McpServerService.McpSessionOptions(McpServerService.AskCommandExecutionMode.DONT_ASK, McpToolFilter.AllowAll),
    JsonObject::class to JsonObject(emptyMap()),
    McpCallHeaders::class to McpCallHeaders(emptyMap()),
    Int::class to 1,
    String::class to "mcp-companion-test-call",
)

private fun argumentFor(parameter: KParameter, values: Map<KClass<*>, Any>): Any? = values[parameter.type.classifier]
    ?: if (parameter.type.isMarkedNullable) null else error("Unsupported McpCallInfo parameter $parameter")
