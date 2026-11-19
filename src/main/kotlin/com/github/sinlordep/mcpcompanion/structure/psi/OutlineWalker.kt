package com.github.sinlordep.mcpcompanion.structure.psi

import com.github.sinlordep.mcpcompanion.structure.OutlineElement
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.usageView.UsageViewTypeLocation

/**
 * Turns a Structure view tree into [OutlineElement]s, in a read action. Language-neutral: names and texts come from
 * the tree elements' presentation, positions from their PSI values, kinds and modifiers from the
 * [DeclarationFactsProvider]s, else from the language's own description of the element (`UsageViewTypeLocation`).
 * Elements of other files (inherited members some views add) are left out; siblings are sorted by their position.
 * Stops after [maxElements] elements and then reports [complete] as false.
 */
internal class OutlineWalker(
    private val file: VirtualFile,
    private val document: Document?,
    private val facts: List<DeclarationFactsProvider>,
    private val maxElements: Int,
) {

    private var count = 0

    var complete: Boolean = true
        private set

    fun children(parent: TreeElement): List<OutlineElement> {
        val nodes = parent.children.mapNotNull { child -> node(child) }
        val sorted = nodes.sortedWith(compareBy(nullsLast()) { it.range?.startOffset })
        val result = ArrayList<OutlineElement>(sorted.size)
        for (node in sorted) {
            ProgressManager.checkCanceled()
            if (count >= maxElements) {
                complete = false
                break
            }
            count++
            result += node.toElement(children(node.tree))
        }
        return result
    }

    private class Node(
        val tree: TreeElement,
        val text: String,
        val psi: PsiElement?,
        val range: TextRange?,
        val nameOffset: Int?,
    )

    private fun node(tree: TreeElement): Node? {
        val psi = (tree as? StructureViewTreeElement)?.value as? PsiElement
        if (psi != null && (!psi.isValid || psi.containingFile?.virtualFile?.let { it != file } == true)) return null
        val text = tree.presentation.presentableText?.trim().orEmpty()
            .ifEmpty { (psi as? PsiNamedElement)?.name.orEmpty() }
        if (text.isEmpty()) return null
        val positioned = (psi as? PsiCompiledElement)?.mirror ?: psi
        val nameOffset = ((positioned as? PsiNameIdentifierOwner)?.nameIdentifier ?: positioned)?.textOffset
        return Node(tree, text, psi, positioned?.textRange, nameOffset)
    }

    private fun Node.toElement(children: List<OutlineElement>): OutlineElement {
        val name = (psi as? PsiNamedElement)?.name?.takeIf { it.isNotBlank() } ?: text
        val known = psi?.let { element -> facts.firstNotNullOfOrNull { it.facts(element) } }
        return OutlineElement(
            name = name.short(),
            kind = known?.kind ?: psi?.let(::describedKind),
            signature = text.takeIf { it != name }?.short(),
            location = tree.presentation.locationString?.trim()?.takeIf { it.isNotEmpty() }?.short(),
            modifiers = known?.modifiers.orEmpty(),
            line = nameOffset?.let(::lineOf),
            startLine = range?.startOffset?.let(::lineOf),
            endLine = range?.let { lineOf((it.endOffset - 1).coerceAtLeast(it.startOffset)) },
            children = children,
        )
    }

    private fun describedKind(element: PsiElement): String? =
        ElementDescriptionUtil.getElementDescription(element, UsageViewTypeLocation.INSTANCE)
            .trim().lowercase().takeIf { it.isNotEmpty() }

    private fun lineOf(offset: Int): Int? {
        val text = document ?: return null
        if (offset < 0 || offset > text.textLength) return null
        return text.getLineNumber(offset) + 1
    }

    private fun String.short(): String = if (length <= MAX_TEXT) this else take(MAX_TEXT - 1) + "…"

    private companion object {
        const val MAX_TEXT = 200
    }
}
