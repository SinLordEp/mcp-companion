package com.github.sinlordep.mcpcompanion.testing

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Calls tools from platform tests the way the MCP server does: off the EDT, inside an MCP call context. The test thread
 * (the EDT) keeps dispatching events meanwhile, so tools that switch to the EDT (safe delete) do not deadlock.
 */
class ToolCalls(private val project: Project) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun served(name: String): McpTool = McpServerService.getInstance().getMcpTools().single {
        it.descriptor.name == name
    }

    /** Starts [block] for tool [name] in the background; finish it with [await]. */
    fun <T> start(name: String, block: suspend () -> T): Deferred<T> {
        val descriptor = served(name).descriptor
        return scope.async { withMcpCallContext(project, descriptor, block) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> await(call: Deferred<T>, seconds: Int = WAIT_SECONDS): T {
        PlatformTestUtil.waitWithEventsDispatching("tool call did not finish", { call.isCompleted }, seconds)
        return call.getCompleted()
    }

    /** Runs a toolset function and returns its output; throws [McpExpectedError] for a tool failure. */
    fun <T> call(name: String, block: suspend () -> T): T = await(start(name, block))

    /** The error code of a tool failure. */
    fun <T> errorCode(name: String, block: suspend () -> T): String {
        val error = try {
            call(name, block)
            throw AssertionError("expected $name to fail")
        } catch (e: McpExpectedError) {
            e
        }
        val details = (error.mcpErrorStructureContent as JsonObject).getValue("error").jsonObject
        return details.getValue("code").jsonPrimitive.content
    }

    /** Calls tool [name] through the server's own [McpTool], with JSON arguments, as a client would. */
    fun callJson(name: String, arguments: JsonObject): McpToolCallResult {
        val tool = served(name)
        return await(scope.async { withMcpCallContext(project, tool.descriptor) { tool.call(arguments) } })
    }

    fun dispose() {
        scope.cancel()
    }

    private companion object {
        const val WAIT_SECONDS = 60
    }
}
