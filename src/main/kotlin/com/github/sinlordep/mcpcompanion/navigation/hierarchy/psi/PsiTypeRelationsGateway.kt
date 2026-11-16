package com.github.sinlordep.mcpcompanion.navigation.hierarchy.psi

import com.github.sinlordep.mcpcompanion.common.paging.BoundedCollector
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolFailures
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle
import com.github.sinlordep.mcpcompanion.common.symbols.psi.PsiSymbolMapper
import com.github.sinlordep.mcpcompanion.common.symbols.psi.psiElement
import com.github.sinlordep.mcpcompanion.navigation.hierarchy.TypeRef
import com.github.sinlordep.mcpcompanion.navigation.hierarchy.TypeRelationsGateway
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiQualifiedNamedElement
import com.intellij.psi.search.GlobalSearchScope

/**
 * Type relations through the [TypeHierarchyProvider]s of the installed languages. Each call is one smart read action
 * (waits for indexing, cancellable, restarted from scratch after a write action), so the keys handed to `choose` and
 * the types described from them always match. Subtypes are searched in project content.
 */
class PsiTypeRelationsGateway(
    private val project: Project,
    private val mapper: PsiSymbolMapper,
    private val providers: () -> List<TypeHierarchyProvider> = { TypeHierarchyProvider.EP.extensionList },
) : TypeRelationsGateway {

    override suspend fun typeOf(target: SymbolHandle): ToolResult<TypeRef> = smartReadAction(project) {
        val element = target.psiElement() ?: return@smartReadAction SymbolFailures.gone()
        val type = providers().firstNotNullOfOrNull { it.typeOf(element) }
            ?: return@smartReadAction ToolResult.failure(
                ErrorCode.UNSUPPORTED,
                "The symbol is not a class or interface that the installed language support can relate.",
                "Point at a class or interface of a JVM language (Java, Kotlin, ...); this needs the Java plugin.",
            )
        ToolResult.success(TypeRef(keyOf(type), mapper.mapOrAnonymous(type)))
    }

    override suspend fun directSupertypes(
        type: TypeRef,
        choose: (List<String>) -> List<String>,
    ): ToolResult<List<TypeRef>> = withType(type) { provider, element ->
        described(provider.directSupertypes(element), choose)
    }

    override suspend fun directSubtypes(
        type: TypeRef,
        limit: Int,
        choose: (List<String>) -> List<String>,
    ): ToolResult<List<TypeRef>> = withType(type) { provider, element ->
        val collector = BoundedCollector<PsiElement>(limit)
        provider.processDirectSubtypes(element, GlobalSearchScope.projectScope(project), collector::offer)
        described(collector.items(), choose)
    }

    private suspend fun <T> withType(type: TypeRef, action: (TypeHierarchyProvider, PsiElement) -> T): ToolResult<T> =
        smartReadAction(project) {
            val element = type.symbol.handle.psiElement() ?: return@smartReadAction SymbolFailures.gone()
            providers().firstNotNullOfOrNull { provider -> provider.typeOf(element)?.let { provider to it } }
                ?.let { (provider, typeElement) -> ToolResult.success(action(provider, typeElement)) }
                ?: SymbolFailures.gone()
        }

    /** Keys only for all [types]; full descriptions only for the chosen ones. */
    private fun described(types: List<PsiElement>, choose: (List<String>) -> List<String>): List<TypeRef> {
        val byKey = LinkedHashMap<String, PsiElement>()
        for (type in types) byKey.putIfAbsent(keyOf(type), type)
        return choose(byKey.keys.toList()).mapNotNull { key ->
            byKey[key]?.let { TypeRef(key, mapper.mapOrAnonymous(it)) }
        }
    }

    /** Qualified name (from stubs, cheap), or file URL and offset for local and anonymous classes. */
    private fun keyOf(type: PsiElement): String = (type as? PsiQualifiedNamedElement)?.qualifiedName
        ?: type.navigationElement.let { "${it.containingFile?.virtualFile?.url}#${it.textOffset}" }
}
