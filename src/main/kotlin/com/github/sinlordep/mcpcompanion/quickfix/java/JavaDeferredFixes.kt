package com.github.sinlordep.mcpcompanion.quickfix.java

import com.github.sinlordep.mcpcompanion.quickfix.psi.DeferredFixProvider
import com.intellij.codeInsight.daemon.impl.quickfix.ImportClassFix
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * Java's "Import class" for an unresolved class reference. The Java plugin registers it lazily from its error fix
 * provider (`registerLazyFixes`, an interface marked `@ApiStatus.Experimental`), so a highlight made without an editor
 * does not carry it. This creates the same fix for the same references: unresolved, not the name of a called method,
 * not in `module-info.java`. `ImportClassFix` and its `getFallbackModCommandAction` (declared in the class
 * `ImportClassFixBase`) are public and not annotated; the method implements a method of the `@Experimental` interface
 * `IntentionActionWithModCommandFallback`, which this class does not use (docs/notes-step9.md). The `ModCommand`
 * form computes the import as data, or offers a choice when several classes have the name. Registered in
 * `mcp-companion-java.xml`.
 */
class JavaDeferredFixes : DeferredFixProvider {

    override fun fixesAt(file: PsiFile, range: TextRange): List<IntentionAction> {
        if (file.name == MODULE_INFO) return emptyList()
        val reference = PsiTreeUtil.getParentOfType(
            file.findElementAt(range.startOffset),
            PsiJavaCodeReferenceElement::class.java,
            false,
        ) ?: return emptyList()
        val unresolved = reference.textRange.intersects(range) && reference.multiResolve(false).isEmpty()
        if (!unresolved || reference.parent is PsiMethodCallExpression) return emptyList()
        // The fix in its ModCommand form (computes the import as data, or offers a choice of classes).
        val fix = ImportClassFix(reference).fallbackModCommandAction ?: return emptyList()
        return listOf(fix.asIntention())
    }

    private companion object {
        const val MODULE_INFO = "module-info.java"
    }
}
