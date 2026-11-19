package com.github.sinlordep.mcpcompanion.run.ide

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement

/**
 * Refuses runs from code that the IDE would only start after asking the user, in its run configuration producers'
 * `onFirstRun`, which `start_at` skips (see [IdeCodeRunGateway]). One per language or test framework, registered
 * from the optional descriptor of the plugin it needs (for Java: `META-INF/mcp-companion-java.xml`).
 */
interface CodeRunGuard {

    /**
     * Why [source] (the element a producer made the configuration for: a test method, class, directory, main
     * function, ...) cannot be run without a question; null when this guard has no objection. Called in a read
     * action.
     */
    fun refusal(source: PsiElement): String?

    companion object {
        val EP: ExtensionPointName<CodeRunGuard> = ExtensionPointName("com.github.sinlordep.mcpcompanion.codeRunGuard")
    }
}
