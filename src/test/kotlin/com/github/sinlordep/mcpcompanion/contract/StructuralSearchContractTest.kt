package com.github.sinlordep.mcpcompanion.contract

import com.github.sinlordep.mcpcompanion.yield.reflectMcpToolNames
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.StructuralSearchUtil
import com.intellij.structuralsearch.plugin.ui.ConfigurationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * What `mc_structural_search` leaves to the bundled server and relies on in the platform (docs/notes-step11.md). If
 * one of these fails after an IDE update, redo that check before changing the expectation.
 */
class StructuralSearchContractTest : BasePlatformTestCase() {

    /** The official tools this IDE's test setup declares (also those it does not serve). */
    private fun officialTools(): Set<String> = McpToolset.EP.extensionList
        .filterNot { it.javaClass.name.startsWith("com.github.sinlordep.mcpcompanion") }
        .flatMap { reflectMcpToolNames(it.javaClass) }
        .toSortedSet()

    /**
     * The official tools that search code or touch frameworks and HTTP. `search_text` and `search_regex` match
     * characters, `search_symbol` declaration names, `search_file` file names; none matches code by its syntax tree
     * with variables and constraints. The inspection scripts (`*_inspection_kts*`) write and run a Kotlin inspection,
     * not a search template. With the IDE's Ultimate features (loaded in tests, absent from an IDE without a
     * subscription), the Spring MVC plugin's `intellij.spring.mvc.ai` module adds `spring_web_controllers`,
     * `spring_web_endpoints`, `spring_web_endpoint_info` and `spring_webflux_functional_routes`, and the OpenAPI plugin
     * `swagger_endpoints` and `swagger_endpoint_info`: the HTTP endpoints of step 11's planned Spring tool are official
     * already. Nothing lists Spring beans or their injections (`mc_spring_beans` does, step 13), or sends an HTTP
     * request. A new name here may duplicate `mc_structural_search` or `mc_spring_beans` (add a catalog entry).
     */
    fun testTheOfficialSearchFrameworkAndHttpToolsAreTheAnalysedOnes() {
        val related = Regex("search|find|struct|pattern|match|spring|bean|endpoint|http|request|rest|url|inspection")
        assertEquals(
            sortedSetOf(
                "compile_inspection_kts",
                "generate_inspection_kts_api",
                "generate_inspection_kts_examples",
                "run_inspection_kts",
                "run_inspection_kts_examples",
                "run_inspection_kts_project",
                "search_file",
                "search_regex",
                "search_symbol",
                "search_text",
                "spring_web_controllers",
                "spring_web_endpoint_info",
                "spring_web_endpoints",
                "spring_webflux_functional_routes",
                "swagger_endpoint_info",
                "swagger_endpoints",
            ),
            officialTools().filter { related.containsMatchIn(it) }.toSortedSet(),
        )
    }

    /** The structural search only reads. */
    fun testHints() {
        val hints = McpServerService.getInstance().getMcpTools().single {
            it.descriptor.name == "mc_structural_search"
        }.descriptor.annotations
        assertEquals(true, hints?.readOnlyHint)
        assertEquals(false, hints?.destructiveHint)
        assertEquals(false, hints?.openWorldHint)
    }

    /**
     * The public Structural Search API the gateway is built on (module `intellij.platform.structuralSearch`, visibility
     * public, nothing used is annotated internal or experimental): validation, the matcher, the languages, the
     * templates of the dialog.
     */
    fun testThePublicStructuralSearchApiIsThere() {
        val matcher = Matcher::class.java.methods.map { it.name }.toSet()
        assertTrue(matcher.toString(), matcher.containsAll(listOf("validate", "findMatches")))
        assertTrue(StructuralSearchUtil.getSuitableFileTypes().any { it.language.displayName == "Java" })
        assertTrue(ConfigurationManager.getInstance(project).allConfigurations.any { it.isPredefined })
        assertNotNull(MatchOptions::class.java.getMethod("addNewVariableConstraint", String::class.java))
    }
}
