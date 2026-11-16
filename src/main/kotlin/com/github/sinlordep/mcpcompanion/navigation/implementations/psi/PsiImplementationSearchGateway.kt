package com.github.sinlordep.mcpcompanion.navigation.implementations.psi

import com.github.sinlordep.mcpcompanion.common.paging.BoundedCollector
import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.mapNotNull
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.scope.psi.PsiSearchScopes
import com.github.sinlordep.mcpcompanion.common.source.SourceKey
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolFailures
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle
import com.github.sinlordep.mcpcompanion.common.symbols.psi.PsiSymbolMapper
import com.github.sinlordep.mcpcompanion.common.symbols.psi.psiElement
import com.github.sinlordep.mcpcompanion.navigation.implementations.ImplementationSearchGateway
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.Processor

/**
 * Downwards with the language-neutral `DefinitionsScopedSearch` (what Go to Implementation uses), so every language
 * plugin contributes; upwards through the [SuperDeclarationProvider]s of the installed languages.
 *
 * Runs in smart read actions: waits for indexing, cancellable, restarted with fresh state after a write action.
 * The downward search reports in no stable order; it builds cheap keys, the use case picks the page, and only the
 * page is described, all in one read action.
 */
class PsiImplementationSearchGateway(
    private val project: Project,
    private val scopes: PsiSearchScopes,
    private val mapper: PsiSymbolMapper,
    private val providers: () -> List<SuperDeclarationProvider> = { SuperDeclarationProvider.EP.extensionList },
) : ImplementationSearchGateway {

    override suspend fun implementations(
        target: SymbolHandle,
        scope: SearchScopeSpec,
        limit: Int,
        pick: (List<SourceKey>) -> ToolResult<Page<SourceKey>>,
    ): ToolResult<Page<LocatedSymbol>> = smartReadAction(project) {
        val element = target.psiElement() ?: return@smartReadAction SymbolFailures.gone()
        scopes.resolve(scope).flatMap { searchScope ->
            val collector = BoundedCollector<PsiElement>(limit)
            val self = element.navigationElement
            DefinitionsScopedSearch.search(element, searchScope, true).forEach(
                Processor { found -> found.navigationElement == self || collector.offer(found) },
            )
            // Keyed by source position: Kotlin may report a declaration both as itself and as its light element.
            val found = LinkedHashMap<SourceKey, PsiElement>()
            for (declaration in collector.items()) found.putIfAbsent(keyOf(declaration), declaration)
            pick(found.keys.toList()).map { page -> page.mapNotNull { key -> found[key]?.let(mapper::mapOrAnonymous) } }
        }
    }

    private fun keyOf(declaration: PsiElement): SourceKey {
        val source = declaration.navigationElement
        return SourceKey(source.containingFile?.virtualFile?.url.orEmpty(), source.textOffset)
    }

    override suspend fun superDeclarations(target: SymbolHandle): ToolResult<List<LocatedSymbol>> =
        smartReadAction(project) {
            val element = target.psiElement() ?: return@smartReadAction SymbolFailures.gone()
            val supers = providers().firstNotNullOfOrNull { it.superDeclarations(element) }
                ?: return@smartReadAction ToolResult.failure(
                    ErrorCode.UNSUPPORTED,
                    "No installed language support knows what this symbol extends or overrides.",
                    "direction=up works for classes and methods of JVM languages (Java, Kotlin, ...) when the " +
                        "Java plugin is installed; use direction=down or mc_find_usages instead.",
                )
            ToolResult.success(supers.map(mapper::mapOrAnonymous))
        }
}
