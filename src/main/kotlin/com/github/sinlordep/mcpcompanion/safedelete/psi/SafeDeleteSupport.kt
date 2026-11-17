package com.github.sinlordep.mcpcompanion.safedelete.psi

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement

/**
 * What safe delete must know about one language family to stay non-interactive and safe: the IDE's own safe delete
 * asks the user about parameters (signature change), super methods and overriding methods; this tool decides those
 * cases by fixed rules instead (see `docs/notes-step2.md`). Registered from the optional descriptor of the language
 * plugin it needs (for JVM languages: `META-INF/mcp-companion-java.xml`). Called inside a read action.
 */
interface SafeDeleteSupport {

    /** Why this tool does not delete [element] at all, e.g. because it is a parameter; null to go on. */
    fun refusal(element: PsiElement): String?

    /** The declarations [element] directly overrides or implements; empty when there are none or not handled. */
    fun overridden(element: PsiElement): List<PsiElement>

    /** Whether [declaration] overrides or implements [target], directly or not. */
    fun overrides(declaration: PsiElement, target: PsiElement): Boolean

    companion object {
        val EP: ExtensionPointName<SafeDeleteSupport> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.safeDeleteSupport")
    }
}
