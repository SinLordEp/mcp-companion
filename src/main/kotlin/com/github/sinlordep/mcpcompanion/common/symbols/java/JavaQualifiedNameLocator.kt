package com.github.sinlordep.mcpcompanion.common.symbols.java

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocator
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.github.sinlordep.mcpcompanion.common.symbols.psi.PsiSymbolMapper
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope

/**
 * Finds Java classes and members by qualified name: `com.example.Foo`, `com.example.Outer.Inner`, or a member as
 * `com.example.Foo#bar` (methods, fields and inner classes). Searches project and library code.
 */
class JavaQualifiedNameLocator(private val project: Project, private val mapper: PsiSymbolMapper) :
    SymbolLocator<SymbolQuery.ByQualifiedName> {

    override suspend fun locate(query: SymbolQuery.ByQualifiedName): ToolResult<LocatedSymbol> =
        smartReadAction(project) { locateNow(query.qualifiedName.trim()) }

    private fun locateNow(qualifiedName: String): ToolResult<LocatedSymbol> {
        val className = qualifiedName.substringBefore(MEMBER_SEPARATOR)
        val memberName = qualifiedName.substringAfter(MEMBER_SEPARATOR, missingDelimiterValue = "")
        if (className.isBlank()) {
            return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "Empty qualified name.",
                "Pass a name such as com.example.Foo or com.example.Foo#bar.",
            )
        }
        val psiClass = JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project))
            ?: return ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "No Java class named $className.",
                "Check the package and class name; nested classes are written Outer.Inner.",
            )
        return if (memberName.isEmpty()) mapped(psiClass) else locateMember(psiClass, memberName)
    }

    private fun locateMember(psiClass: PsiClass, memberName: String): ToolResult<LocatedSymbol> {
        val candidates: List<PsiElement> = psiClass.findMethodsByName(memberName, false).toList() +
            listOfNotNull(psiClass.findFieldByName(memberName, false), psiClass.findInnerClassByName(memberName, false))
        return when (candidates.size) {
            0 -> ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "${psiClass.qualifiedName} has no member named $memberName.",
                "Check the member name; inherited members must be looked up on the declaring class.",
            )

            1 -> mapped(candidates.single())

            else -> ToolResult.failure(
                ErrorCode.AMBIGUOUS,
                "${psiClass.qualifiedName}#$memberName matches ${candidates.size} declarations (overloads).",
                "Locate the wanted overload by file path, line and column instead.",
            )
        }
    }

    private fun mapped(element: PsiElement): ToolResult<LocatedSymbol> =
        mapper.map(element)?.let { ToolResult.success(it) }
            ?: ToolResult.failure(ErrorCode.NOT_FOUND, "The declaration has no name.")

    private companion object {
        const val MEMBER_SEPARATOR = '#'
    }
}
