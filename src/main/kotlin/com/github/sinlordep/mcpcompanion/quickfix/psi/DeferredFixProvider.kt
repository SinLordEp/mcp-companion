package com.github.sinlordep.mcpcompanion.quickfix.psi

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * The fixes a language registers on its problems only lazily: the IDE computes them when Alt+Enter is pressed, through
 * an API that is internal (`LazyQuickFixUpdater`), so a highlight computed without an editor does not carry them.
 * Java's "Import class" is one. A provider creates the same fixes for the problem at [range] of [file]. One per
 * language, registered from the optional descriptor of the plugin it needs (for Java:
 * `META-INF/mcp-companion-java.xml`).
 */
interface DeferredFixProvider {

    /** The lazily registered fixes of the problem at [range]; empty when there are none. Called in a read action. */
    fun fixesAt(file: PsiFile, range: TextRange): List<IntentionAction>

    companion object {
        val EP: ExtensionPointName<DeferredFixProvider> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.deferredFixes")
    }
}
