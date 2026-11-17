package com.github.sinlordep.mcpcompanion.yield

import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.allSuperclasses
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions

/**
 * Reads the MCP tool names a toolset class declares, memoised per class. A [ClassValue] does not keep unloaded
 * plugins' classes alive.
 *
 * Foreign classes may fail to reflect (a missing optional dependency, Kotlin reflection limits, ...). Such a class
 * contributes no names, the failure is logged once at warn level, and the empty result is memoised on purpose: the
 * reflection of a loaded class is deterministic, so retrying would only repeat the cost and the log line; a reloaded
 * plugin brings a new class and is read afresh. Cancellation and control-flow exceptions are never swallowed, and
 * they are not memoised because [ClassValue] does not cache a thrown exception.
 *
 * @param reflect the reflection step; replaceable in tests.
 */
internal class McpToolNameReader(private val reflect: (Class<*>) -> Set<String> = ::reflectMcpToolNames) {

    private val cache = object : ClassValue<Set<String>>() {
        override fun computeValue(type: Class<*>): Set<String> = readContained(type)
    }

    fun toolNamesOf(type: Class<*>): Set<String> = cache.get(type)

    @Suppress("TooGenericExceptionCaught") // Any failure of foreign code must stay contained; see the class comment.
    private fun readContained(type: Class<*>): Set<String> = try {
        reflect(type)
    } catch (e: Throwable) {
        if (e is ControlFlowException || e is CancellationException || e is OutOfMemoryError) throw e
        LOG.warn("Cannot read MCP tool names of ${type.name}; treating it as declaring none", e)
        emptySet()
    }

    private companion object {
        val LOG = logger<McpToolNameReader>()
    }
}

/**
 * The MCP server's own rules: a member function is a tool when it, or the supertype function it overrides, carries
 * `@McpTool`; the name is `@McpTool.name`, or the function name when that is blank.
 */
internal fun reflectMcpToolNames(type: Class<*>): Set<String> {
    val kotlinType = type.kotlin
    return kotlinType.memberFunctions.mapNotNullTo(linkedSetOf()) { function ->
        annotationOf(kotlinType, function)?.name?.ifBlank { function.name }
    }
}

private fun annotationOf(type: KClass<*>, function: KFunction<*>): McpTool? = function.findAnnotation<McpTool>()
    ?: type.allSuperclasses.asSequence()
        .flatMap { it.declaredMemberFunctions }
        .filter { it.name == function.name }
        .firstNotNullOfOrNull { it.findAnnotation<McpTool>() }
