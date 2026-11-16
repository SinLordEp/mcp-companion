package com.github.sinlordep.mcpcompanion.common.symbols.psi

import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocator
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Contributes a qualified-name [SymbolLocator] for one language. Registered from the optional descriptor of the
 * language plugin it needs (for Java: `META-INF/mcp-companion-java.xml`), so the plugin still loads without it.
 */
interface QualifiedNameLocatorProvider {

    fun create(project: Project, mapper: PsiSymbolMapper): SymbolLocator<SymbolQuery.ByQualifiedName>

    companion object {
        val EP: ExtensionPointName<QualifiedNameLocatorProvider> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.qualifiedNameLocator")
    }
}
