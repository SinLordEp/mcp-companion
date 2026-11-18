package com.github.sinlordep.mcpcompanion.contract

import com.github.sinlordep.mcpcompanion.toolsets.StatusToolset
import com.github.sinlordep.mcpcompanion.yield.reflectMcpToolNames
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Regression tests of the MCP server behaviour that stepping aside relies on (docs/spike-step0.md, Q2). If one fails
 * after an IDE update, the official-tool gateway in `yield/` no longer sees what the server serves.
 */
class ForeignToolEnumerationContractTest : BasePlatformTestCase() {

    /**
     * Our reflection must reproduce the server's tool names exactly, for every enabled toolset. Uses the raw reflection
     * step, so a class that fails to reflect fails this test loudly instead of being skipped.
     */
    fun testReflectionOverTheToolsetEpMatchesTheServersOwnList() {
        val fromServer = McpToolsProvider.EP.extensionList.flatMap { it.getTools() }.map { it.descriptor.name }.toSet()
        val fromReflection = McpToolset.enabledToolsets.flatMap { reflectMcpToolNames(it.javaClass) }.toSet()
        assertEquals(
            "only in server: ${(fromServer - fromReflection).sorted()}; " +
                "only in reflection: ${(fromReflection - fromServer).sorted()}",
            fromServer,
            fromReflection,
        )
    }

    /**
     * Tools of a provider other than the server's reflection provider would be invisible to our gateway. None exists
     * in IU 2026.3; when one appears, the gateway must learn to read it.
     */
    fun testTheReflectionProviderIsTheOnlyToolsProvider() {
        assertEquals(
            listOf("com.intellij.mcpserver.impl.ReflectionToolsProvider"),
            McpToolsProvider.EP.extensionList.map { it.javaClass.name },
        )
    }

    fun testOfficialToolsUsedInTestsExist() {
        val names = McpToolsProvider.EP.extensionList.flatMap { it.getTools() }.map { it.descriptor.name }
        assertContainsElements(names, "get_file_problems", "search_symbol", "rename_refactoring")
    }

    /** Why our toolsets are told apart by a marker interface: in tests every plugin shares one classloader. */
    fun testClassLoaderIdentityDoesNotSeparatePluginsInTests() {
        val ownLoader = StatusToolset::class.java.classLoader
        val sharing = McpToolset.EP.extensionList.filter { it.javaClass.classLoader === ownLoader }
        assertTrue(sharing.size > 1)
    }
}
