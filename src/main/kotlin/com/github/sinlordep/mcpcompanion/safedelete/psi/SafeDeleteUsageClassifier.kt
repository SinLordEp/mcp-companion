package com.github.sinlordep.mcpcompanion.safedelete.psi

import com.github.sinlordep.mcpcompanion.common.source.psi.PsiCodeSites
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.psi.PsiSymbolMapper
import com.github.sinlordep.mcpcompanion.safedelete.Blocker
import com.github.sinlordep.mcpcompanion.safedelete.BlockerKind
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceUsageInfo
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

/**
 * Sorts the platform's safe-delete findings into blockers, cleanups and declarations deleted along. Call inside a
 * read action.
 *
 * - A reference is a cleanup only when the language marks it safe to delete (e.g. an import) and it is not in
 *   generated code, as in the platform; any other reference blocks.
 * - A dependent declaration that overrides or implements the target (per [SafeDeleteSupport]) is deleted along, but
 *   only if nothing references it from outside the deleted declarations; any other dependent declaration blocks.
 * - A target that itself overrides or implements another declaration is blocked by a conflict: deleting it alone
 *   would break its class or change its behaviour.
 * - Language conflicts block.
 */
class SafeDeleteUsageClassifier(
    private val project: Project,
    private val sites: PsiCodeSites,
    private val mapper: PsiSymbolMapper,
) {

    /**
     * [cleanups] are the usages the deletion performs (e.g. removing an import); [overriders] are the declarations
     * deleted along, [alsoDeleted] describes them.
     */
    class Classified(
        val blockers: List<Blocker>,
        val cleanups: List<UsageInfo>,
        val overriders: List<PsiElement>,
        val alsoDeleted: List<LocatedSymbol>,
    )

    fun classify(
        target: PsiElement,
        usages: Array<UsageInfo>,
        conflicts: MultiMap<PsiElement, String>,
        support: SafeDeleteSupport?,
    ): Classified {
        val blockers = ArrayList<Blocker>()
        val overriders = LinkedHashSet<PsiElement>()
        val cleanups = ArrayList<UsageInfo>()
        for (usage in usages) {
            val element = usage.element
            when {
                isCleanup(usage) -> cleanups += usage

                usage is SafeDeleteReferenceUsageInfo || usage !is SafeDeleteUsageInfo ->
                    blockers += Blocker(BlockerKind.REFERENCE, siteOf(usage), null)

                // An overrider may come with several usages (e.g. delete it, or make it private); it is deleted once.
                element != null && support?.overrides(element, target) == true -> overriders += element

                else -> blockers += Blocker(BlockerKind.DEPENDENT, siteOf(usage), null)
            }
        }
        blockers += referencesFromOutside(overriders.toList(), deleted = overriders + target)
        blockers += overriddenBy(target, support)
        blockers += conflictsOf(conflicts)
        return Classified(blockers, cleanups, overriders.toList(), overriders.map(mapper::mapOrAnonymous))
    }

    private fun isCleanup(usage: UsageInfo): Boolean {
        if (usage !is SafeDeleteReferenceUsageInfo || !usage.isSafeDelete) return false
        val file = usage.virtualFile ?: return true
        return !GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, project)
    }

    private fun referencesFromOutside(declarations: List<PsiElement>, deleted: Collection<PsiElement>): List<Blocker> =
        declarations.flatMap { declaration ->
            ReferencesSearch.search(declaration, declaration.useScope).findAll()
                .filterNot { reference -> deleted.any { PsiTreeUtil.isAncestor(it, reference.element, false) } }
                .map { Blocker(BlockerKind.REFERENCE, sites.ofReference(it), null) }
        }

    private fun overriddenBy(target: PsiElement, support: SafeDeleteSupport?): List<Blocker> =
        support?.overridden(target).orEmpty().map { overridden ->
            val site = overridden.navigationElement.let { sites.at(it, it.textOffset) }
            val name = listOfNotNull(
                site?.enclosingDeclaration,
                (overridden as? PsiNamedElement)?.name,
            ).joinToString(".")
            Blocker(
                BlockerKind.CONFLICT,
                site,
                "It overrides or implements $name; deleting it alone would break its class or change its behaviour. " +
                    "Delete $name instead (unused overriding methods go with it), or keep this one.",
            )
        }

    private fun conflictsOf(conflicts: MultiMap<PsiElement, String>): List<Blocker> =
        conflicts.entrySet().flatMap { (element, messages) ->
            val site = sites.at(element, element.textOffset)
            messages.map { Blocker(BlockerKind.CONFLICT, site, StringUtil.removeHtmlTags(it, true)) }
        }

    private fun siteOf(usage: UsageInfo) = usage.element?.let { sites.at(it, usage.navigationOffset) }
}
