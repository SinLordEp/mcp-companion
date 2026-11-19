package com.github.sinlordep.mcpcompanion.quickfix.psi

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.daemon.QuickFixActionRegistrar
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.quickfix.UnresolvedReferenceQuickFixProvider
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference

/** A fix the IDE offers for a problem, and where it applies (the offset an editor's caret would be at). */
internal class FixCandidate(val action: IntentionAction, val offset: Int)

/**
 * The quick fixes of one problem, as Alt+Enter on it lists them (without the inspection options such as "suppress"
 * or "edit inspection settings"):
 *
 * 1. the fixes registered on the highlight (`HighlightInfo.findRegisteredQuickFix`, public);
 * 2. the fixes a language registers lazily, which a highlight made without an editor lacks: from the
 *    [DeferredFixProvider]s, and, for unresolved references, from the platform's
 *    `unresolvedReferenceQuickFixProvider` extensions (each provider's public `registerFixes`, the loop the IDE's own
 *    internal `registerReferenceFixes` runs).
 *
 * The same fix registered twice (same text) is kept once. Call in a read action.
 */
internal class FixCandidates(private val deferred: () -> List<DeferredFixProvider>) {

    fun of(info: HighlightInfo, file: PsiFile): List<FixCandidate> {
        val found = ArrayList<FixCandidate>()
        info.findRegisteredQuickFix<Unit> { descriptor, range ->
            found += FixCandidate(descriptor.action, range.startOffset)
            null
        }
        val range = TextRange(info.startOffset, info.endOffset)
        deferred().flatMap { it.fixesAt(file, range) }.forEach { found += FixCandidate(it, range.startOffset) }
        unresolvedReferenceAt(file, range)?.let { reference ->
            unresolvedReferenceFixes(reference).forEach { found += FixCandidate(it, range.startOffset) }
        }
        return found.distinctBy { it.action.text.ifBlank { it.action.familyName } }
    }

    private fun unresolvedReferenceAt(file: PsiFile, range: TextRange): PsiReference? {
        val reference = file.findReferenceAt(range.startOffset) ?: return null
        val unresolved = when (reference) {
            is PsiPolyVariantReference -> reference.multiResolve(false).isEmpty()
            else -> reference.resolve() == null
        }
        return reference.takeIf { unresolved }
    }

    private fun unresolvedReferenceFixes(reference: PsiReference): List<IntentionAction> {
        val actions = ArrayList<IntentionAction>()
        val registrar = object : QuickFixActionRegistrar {
            override fun register(action: IntentionAction) {
                actions += action
            }

            override fun register(fixRange: TextRange, action: IntentionAction, key: HighlightDisplayKey?) {
                actions += action
            }
        }
        UNRESOLVED_REFERENCE_FIXES.extensionList
            .filter { it.referenceClass.isInstance(reference) }
            .forEach { provider ->
                @Suppress("UNCHECKED_CAST") // Checked by referenceClass above.
                (provider as UnresolvedReferenceQuickFixProvider<PsiReference>).registerFixes(reference, registrar)
            }
        return actions
    }

    private companion object {
        /** The platform's extension point; its own constant is private. */
        val UNRESOLVED_REFERENCE_FIXES: ExtensionPointName<UnresolvedReferenceQuickFixProvider<*>> =
            ExtensionPointName("com.intellij.codeInsight.unresolvedReferenceQuickFixProvider")
    }
}
