package com.github.sinlordep.mcpcompanion.structuralsearch.ssr

import com.github.sinlordep.mcpcompanion.common.source.SourceKey
import com.github.sinlordep.mcpcompanion.common.source.psi.PsiCodeSites
import com.github.sinlordep.mcpcompanion.structuralsearch.MatchedVariable
import com.github.sinlordep.mcpcompanion.structuralsearch.StructuralMatch
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.structuralsearch.MatchResult
import com.intellij.structuralsearch.plugin.ui.Configuration

/**
 * Describes one match for output: its place (through the shared [PsiCodeSites]), its last line, its text and the
 * values of the template's variables. Long texts are cut. Call inside a read action.
 */
internal class MatchDescriptions(private val project: Project, private val sites: PsiCodeSites) {

    fun describe(key: SourceKey, result: MatchResult): StructuralMatch? {
        val element = result.match?.takeIf { it.isValid } ?: return null
        val site = sites.at(element, key.offset) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile) ?: return null
        val end = (element.textRange.endOffset).coerceIn(key.offset, document.textLength)
        val text = result.matchImage ?: element.text
        return StructuralMatch(
            site = site,
            endLine = document.getLineNumber(end) + 1,
            text = text.take(MAX_TEXT),
            textTruncated = text.length > MAX_TEXT,
            variables = variablesOf(result),
        )
    }

    /** The named sub-results: one per variable, all values of a variable that matched several elements together. */
    private fun variablesOf(result: MatchResult): List<MatchedVariable> {
        val values = LinkedHashMap<String, MutableList<String>>()
        collect(result, values)
        return values.map { (name, all) ->
            MatchedVariable(
                name = name,
                values = all.take(MAX_VALUES).map { it.take(MAX_VALUE_LENGTH) },
                truncated = all.size > MAX_VALUES || all.any { it.length > MAX_VALUE_LENGTH },
            )
        }
    }

    /**
     * Walks the whole result tree: a variable's sub-result can hold the results of the variables inside it. Names the
     * matcher makes up for itself (`__catch_18`, the context variable) and the line markers are not variables.
     */
    private fun collect(result: MatchResult, values: MutableMap<String, MutableList<String>>) {
        for (child in result.children) {
            val name = child.name?.takeUnless { it.startsWith(INTERNAL_PREFIX) || it in UNNAMED }
            when {
                name == null -> Unit

                child.isMultipleMatch -> child.children.forEach { part ->
                    values.getOrPut(name) { ArrayList() } += part.matchImage.orEmpty()
                }

                else -> values.getOrPut(name) { ArrayList() } += child.matchImage.orEmpty()
            }
            if (child.isMultipleMatch) child.children.forEach { collect(it, values) } else collect(child, values)
        }
    }

    companion object {
        const val MAX_TEXT: Int = 400
        const val MAX_VALUES: Int = 20
        const val MAX_VALUE_LENGTH: Int = 200

        private const val INTERNAL_PREFIX = "__"
        private val UNNAMED =
            setOf(MatchResult.LINE_MATCH, MatchResult.MULTI_LINE_MATCH, Configuration.CONTEXT_VAR_NAME)
    }
}
