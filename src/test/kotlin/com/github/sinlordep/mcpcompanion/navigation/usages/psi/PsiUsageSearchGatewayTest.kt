package com.github.sinlordep.mcpcompanion.navigation.usages.psi

import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.paging.StableResults
import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.scope.psi.PsiSearchScopes
import com.github.sinlordep.mcpcompanion.common.source.CodeSite
import com.github.sinlordep.mcpcompanion.common.source.psi.PsiCodeSites
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.github.sinlordep.mcpcompanion.common.symbols.psi.symbolLocatorsFor
import com.github.sinlordep.mcpcompanion.navigation.usages.UsageKey
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.value
import com.intellij.psi.PsiReference
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.atomic.AtomicInteger

/** The real search builds keys for every reference but describes only the picked page. */
class PsiUsageSearchGatewayTest : BasePlatformTestCase() {

    private class CountingSites(files: ProjectFiles, project: com.intellij.openapi.project.Project) :
        PsiCodeSites(project, files) {
        val described = AtomicInteger()

        override fun ofReference(reference: PsiReference): CodeSite? =
            super.ofReference(reference).also { described.incrementAndGet() }
    }

    fun testOnlyThePickedPageIsDescribed() = timeoutRunBlocking {
        val calls = (1..30).joinToString("\n") { "        n = n + $it;" }
        myFixture.addFileToProject(
            "a/Counter.java",
            "package a;\npublic class Counter {\n    int n;\n    void up() {\n$calls\n    }\n}",
        )
        val files = ProjectFiles.of(project)
        val sites = CountingSites(files, project)
        val gateway = PsiUsageSearchGateway(project, PsiSearchScopes(project, files), sites)
        val target = symbolLocatorsFor(project).locate(SymbolQuery.ByQualifiedName("a.Counter#n")).value()
        var keys = 0
        val page = gateway.findUsages(
            target.handle,
            SearchScopeSpec.Project,
            StableResults.SEARCH_LIMIT,
            false,
        ) { all ->
            keys = all.size
            StableResults.page(all, UsageKey.ORDER, PageRequest.of(2, 10).value())
        }
        assertTrue(page is ToolResult.Success)
        assertEquals("every reference has a key (n read and n written per line)", 60, keys)
        assertEquals(2, page.value().items.size)
        assertEquals("only the page is described", 2, sites.described.get())
        assertEquals(listOf(10, 10), page.value().items.map { it.site.location.line })
    }
}
