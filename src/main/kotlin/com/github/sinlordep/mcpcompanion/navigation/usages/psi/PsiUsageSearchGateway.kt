package com.github.sinlordep.mcpcompanion.navigation.usages.psi

import com.github.sinlordep.mcpcompanion.common.paging.BoundedCollector
import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.mapNotNull
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.scope.psi.PsiSearchScopes
import com.github.sinlordep.mcpcompanion.common.source.psi.PsiCodeSites
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolFailures
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle
import com.github.sinlordep.mcpcompanion.common.symbols.psi.psiElement
import com.github.sinlordep.mcpcompanion.navigation.usages.AccessKind
import com.github.sinlordep.mcpcompanion.navigation.usages.Usage
import com.github.sinlordep.mcpcompanion.navigation.usages.UsageKey
import com.github.sinlordep.mcpcompanion.navigation.usages.UsageSearchGateway
import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

/**
 * Finds usages with the language-neutral `ReferencesSearch`, so every language plugin contributes its references.
 * Read/write access comes from the language's `ReadWriteAccessDetector`, where there is one.
 *
 * Everything happens in one smart read action: it waits for indexing, is cancellable, and restarts from scratch when
 * a write action interrupts it, so the picked keys always match the references they are described from. The search
 * builds only cheap keys (file URL and offset, access kind on request); only the picked page is described.
 */
class PsiUsageSearchGateway(
    private val project: Project,
    private val scopes: PsiSearchScopes,
    private val sites: PsiCodeSites,
) : UsageSearchGateway {

    override suspend fun distinguishesAccess(target: SymbolHandle): ToolResult<Boolean> = smartReadAction(project) {
        val element = target.psiElement() ?: return@smartReadAction SymbolFailures.gone()
        ToolResult.success(accessDetectorFor(element) != null)
    }

    override suspend fun findUsages(
        target: SymbolHandle,
        scope: SearchScopeSpec,
        limit: Int,
        withAccess: Boolean,
        pick: (List<UsageKey>) -> ToolResult<Page<UsageKey>>,
    ): ToolResult<Page<Usage>> = smartReadAction(project) {
        val element = target.psiElement() ?: return@smartReadAction SymbolFailures.gone()
        scopes.resolve(scope).flatMap { searchScope ->
            val detector = accessDetectorFor(element)
            val collector = BoundedCollector<PsiReference>(limit)
            ReferencesSearch.search(element, searchScope).forEach(Processor(collector::offer))
            // Keyed by position: a reference reported twice (e.g. as a light and as a source element) counts once.
            val references = HashMap<UsageKey, PsiReference>()
            for (reference in collector.items()) {
                val source = sites.keyOf(reference) ?: continue
                val access = if (withAccess) detector?.let { accessOf(it, element, reference) } else null
                references.putIfAbsent(UsageKey(source, access), reference)
            }
            pick(references.keys.toList()).map { page ->
                page.mapNotNull { key -> references[key]?.let { describe(it, element, detector) } }
            }
        }
    }

    private fun describe(reference: PsiReference, target: PsiElement, detector: ReadWriteAccessDetector?): Usage? =
        sites.ofReference(reference)?.let { site -> Usage(site, detector?.let { accessOf(it, target, reference) }) }

    private fun accessDetectorFor(element: PsiElement): ReadWriteAccessDetector? =
        ReadWriteAccessDetector.findDetector(element)?.takeIf { it.isReadWriteAccessible(element) }

    private fun accessOf(detector: ReadWriteAccessDetector, target: PsiElement, reference: PsiReference): AccessKind =
        when (detector.getReferenceAccess(target, reference)) {
            ReadWriteAccessDetector.Access.Read -> AccessKind.READ
            ReadWriteAccessDetector.Access.Write -> AccessKind.WRITE
            ReadWriteAccessDetector.Access.ReadWrite -> AccessKind.READ_WRITE
        }
}
