package com.github.sinlordep.mcpcompanion.common.symbols.java

import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocator
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.github.sinlordep.mcpcompanion.common.symbols.psi.PsiSymbolMapper
import com.github.sinlordep.mcpcompanion.common.symbols.psi.QualifiedNameLocatorProvider
import com.intellij.openapi.project.Project

/** Registered in `META-INF/mcp-companion-java.xml`, which only loads when the Java plugin is present. */
class JavaQualifiedNameLocatorProvider : QualifiedNameLocatorProvider {
    override fun create(project: Project, mapper: PsiSymbolMapper): SymbolLocator<SymbolQuery.ByQualifiedName> =
        JavaQualifiedNameLocator(project, mapper)
}
