package com.github.sinlordep.mcpcompanion.guide

import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.yield.reflectMcpToolNames
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The guide names exactly the plugin's tools: as registered in plugin.xml and declared by `@McpTool`. */
class AgentGuideToolNamesTest : BasePlatformTestCase() {

    fun testTheGuideNamesEveryToolOfThePluginAndNoOther() {
        val tools = McpToolset.EP.extensionList.filterIsInstance<CompanionToolset>()
            .flatMap { reflectMcpToolNames(it.javaClass) }
            .toSortedSet()
        assertTrue("found $tools", tools.size >= 2 && tools.all { it.startsWith("mc_") })

        val mentioned = Regex("""\bmc_[a-z_]*[a-z]""").findAll(AgentGuide().text()).map { it.value }.toSortedSet()

        assertEquals("tools the guide mentions that do not exist", emptySet<String>(), mentioned - tools)
        assertEquals("tools the guide does not mention", emptySet<String>(), tools - mentioned)
    }
}
