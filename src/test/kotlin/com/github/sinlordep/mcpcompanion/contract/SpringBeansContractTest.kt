package com.github.sinlordep.mcpcompanion.contract

import com.github.sinlordep.mcpcompanion.yield.reflectMcpToolNames
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.module.Module
import com.intellij.spring.CommonSpringModel
import com.intellij.spring.SpringManager
import com.intellij.spring.model.SpringBeanPointer
import com.intellij.spring.model.SpringModelSearchParameters
import com.intellij.spring.model.utils.SpringAutowireUtils
import com.intellij.spring.model.utils.SpringModelSearchers
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * What `mc_spring_beans` leaves to the bundled server and relies on in the Spring plugin (docs/notes-step13.md). If one
 * of these fails after an IDE update, redo that check before changing the expectation.
 */
class SpringBeansContractTest : BasePlatformTestCase() {

    private fun officialTools(): Set<String> = McpToolset.EP.extensionList
        .filterNot { it.javaClass.name.startsWith("com.github.sinlordep.mcpcompanion") }
        .flatMap { reflectMcpToolNames(it.javaClass) }
        .toSortedSet()

    /**
     * The official Spring tools (with the IDE's paid features, loaded in tests) are about web endpoints and Spring Boot
     * configuration; none lists beans or their injections. A new name here may duplicate `mc_spring_beans`: check it
     * and add a catalog entry (mcp-companion/official-equivalents.txt) if it does.
     */
    fun testNoOfficialToolListsBeansOrInjections() {
        val spring = Regex("spring|bean|inject|autowir|configuration_")
        assertEquals(
            sortedSetOf(
                "get_configuration_files",
                "get_configuration_properties",
                "get_configuration_values",
                "spring_web_controllers",
                "spring_web_endpoint_info",
                "spring_web_endpoints",
                "spring_webflux_functional_routes",
            ),
            officialTools().filter { spring.containsMatchIn(it) }.toSortedSet(),
        )
    }

    /** The bean view only reads. */
    fun testHints() {
        val hints = McpServerService.getInstance().getMcpTools().single {
            it.descriptor.name == "mc_spring_beans"
        }.descriptor.annotations
        assertEquals(true, hints?.readOnlyHint)
        assertEquals(false, hints?.destructiveHint)
        assertEquals(false, hints?.openWorldHint)
    }

    /**
     * The public Spring API the gateway is built on (content module `intellij.spring`, visibility public, none of the
     * members used annotated internal or experimental; the Plugin Verifier checks the rest).
     */
    fun testThePublicSpringApiIsThere() {
        assertNotNull(SpringManager::class.java.getMethod("getAllModels", Module::class.java))
        assertNotNull(CommonSpringModel::class.java.getMethod("getAllCommonBeans"))
        assertNotNull(
            SpringModelSearchers::class.java.getMethod(
                "findBeans",
                CommonSpringModel::class.java,
                SpringModelSearchParameters.BeanClass::class.java,
            ),
        )
        assertNotNull(SpringBeanPointer::class.java.getMethod("getEffectiveBeanTypes"))
        assertNotNull(SpringAutowireUtils.getInstance())
    }
}
