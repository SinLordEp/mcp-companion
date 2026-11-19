package com.github.sinlordep.mcpcompanion.structure.psi

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement

/**
 * What a language support knows about a declaration beyond the Structure view: a stable [kind] (`class`, `interface`,
 * `method`, `constructor`, `field`, ...) and its [modifiers] (visibility first). Either may be absent.
 */
data class DeclarationFacts(val kind: String?, val modifiers: List<String>)

/**
 * Extension point `com.github.sinlordep.mcpcompanion.declarationFacts`: kinds and modifiers of the declarations of
 * some languages (JVM languages through UAST, in `mcp-companion-java.xml`). Without one, the kind is the language's
 * own term for the element (as Find Usages names it) and no modifiers are given.
 */
interface DeclarationFactsProvider {

    /** Facts about [element], or null when this provider does not know it. Called in a read action. */
    fun facts(element: PsiElement): DeclarationFacts?

    companion object {
        val EP: ExtensionPointName<DeclarationFactsProvider> =
            ExtensionPointName.create("com.github.sinlordep.mcpcompanion.declarationFacts")
    }
}
