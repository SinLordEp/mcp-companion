package com.github.sinlordep.mcpcompanion.common.source.psi

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement

/**
 * Tells parameters and local variables of one language family apart from members and types, so that the enclosing
 * declaration of a code site names the member (`Foo.bar`), not a parameter in it. Registered from the optional
 * descriptor of the language plugin it needs (for JVM languages: `META-INF/mcp-companion-java.xml`).
 */
interface LocalDeclarationDetector {

    /** True for a parameter or local variable, false for another declaration, null when not handled here. */
    fun isLocal(declaration: PsiElement): Boolean?

    companion object {
        val EP: ExtensionPointName<LocalDeclarationDetector> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.localDeclarations")
    }
}
