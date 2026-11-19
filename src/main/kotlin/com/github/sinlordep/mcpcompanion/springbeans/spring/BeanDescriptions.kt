package com.github.sinlordep.mcpcompanion.springbeans.spring

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.source.psi.PsiCodeSites
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocation
import com.github.sinlordep.mcpcompanion.springbeans.BeanKind
import com.github.sinlordep.mcpcompanion.springbeans.BeanSummary
import com.github.sinlordep.mcpcompanion.springbeans.SpringBean
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.spring.model.SpringBeanPointer
import com.intellij.spring.model.SpringProfile
import com.intellij.spring.model.jam.JamPsiClassSpringBean
import com.intellij.spring.model.jam.JamPsiMethodSpringBean
import com.intellij.spring.model.scope.SpringBeanScope
import com.intellij.spring.model.xml.DomSpringBean

/**
 * Describes the beans of the IDE's Spring model without platform types: cheap facts ([summaryOf]) for filtering and
 * paging, the full description ([describe]) for what is shown. A bean is identified by its definition (file and
 * offset) and name, so the same bean seen through the models of several modules is one bean. Call inside a read
 * action.
 */
internal class BeanDescriptions(private val files: ProjectFiles, private val sites: PsiCodeSites) {

    /**
     * The class, `@Bean` method or XML tag that defines the bean. The pointer's own element can be a naming target
     * that stands for the whole file (seen: line 1 for components and beans with several names), so the bean's
     * declaration comes first.
     */
    fun definitionOf(pointer: SpringBeanPointer<*>): PsiElement? {
        val declaration = when (val bean = pointer.springBean) {
            is JamPsiClassSpringBean -> bean.psiElement
            is JamPsiMethodSpringBean -> bean.psiElement
            is DomSpringBean -> bean.xmlTag
            else -> null
        }
        return (declaration ?: pointer.psiElement)?.takeIf { it.isValid }
    }

    fun idOf(pointer: SpringBeanPointer<*>): String {
        val element = definitionOf(pointer)
        val file = element?.containingFile?.virtualFile
        return if (element != null && file != null) {
            "${file.url}#${element.textRange.startOffset}#${pointer.name.orEmpty()}"
        } else {
            "#${pointer.name.orEmpty()}#${pointer.beanClass?.qualifiedName.orEmpty()}"
        }
    }

    /** Where the bean is defined; a bean without a file (registered by code or the IDE) is [FileOrigin.OTHER]. */
    fun originOf(pointer: SpringBeanPointer<*>): FileOrigin =
        definitionOf(pointer)?.containingFile?.virtualFile?.let(files::originOf) ?: FileOrigin.OTHER

    fun summaryOf(pointer: SpringBeanPointer<*>): BeanSummary = BeanSummary(
        id = idOf(pointer),
        name = pointer.name,
        aliases = aliasesOf(pointer),
        type = typeOf(pointer),
        library = originOf(pointer) != FileOrigin.PROJECT,
    )

    fun describe(pointer: SpringBeanPointer<*>): SpringBean {
        val bean = pointer.springBean
        val definition = definitionOf(pointer)
        return SpringBean(
            id = idOf(pointer),
            name = pointer.name,
            aliases = aliasesOf(pointer),
            type = typeOf(pointer),
            kind = kindOf(pointer),
            scope = bean.springScope?.value ?: SpringBeanScope.SINGLETON_SCOPE.value,
            profiles = profilesOf(bean.profile),
            primary = bean.isPrimary,
            fallback = bean.isFallback,
            qualifiers = bean.springQualifiers.mapNotNull { it.qualifierValue }.distinct(),
            module = definition?.let { ModuleUtilCore.findModuleForPsiElement(it)?.name },
            location = definition?.let(::locationOf),
        )
    }

    /** The place of [element]'s name (a class's or method's name), or of the element itself (an XML tag). */
    fun locationOf(element: PsiElement): SymbolLocation? {
        val named = (element as? PsiNameIdentifierOwner)?.nameIdentifier ?: element
        return sites.at(named, named.textRange.startOffset)?.location
    }

    private fun aliasesOf(pointer: SpringBeanPointer<*>): List<String> =
        pointer.aliases.filterNot { it.isBlank() || it == pointer.name }.distinct()

    private fun typeOf(pointer: SpringBeanPointer<*>): String? =
        pointer.beanClass?.qualifiedName ?: pointer.springBean.beanType?.canonicalText

    fun kindOf(pointer: SpringBeanPointer<*>): BeanKind = when (pointer.springBean) {
        is JamPsiMethodSpringBean -> BeanKind.BEAN_METHOD
        is JamPsiClassSpringBean -> BeanKind.COMPONENT
        is DomSpringBean -> BeanKind.XML
        else -> BeanKind.OTHER
    }

    /** The profile expressions a bean is limited to; none for a bean of the default profile. */
    private fun profilesOf(profile: SpringProfile): List<String> =
        profile.expressions.filterNot { it in SpringProfile.DEFAULT_PROFILE_NAMES }.sorted()
}
