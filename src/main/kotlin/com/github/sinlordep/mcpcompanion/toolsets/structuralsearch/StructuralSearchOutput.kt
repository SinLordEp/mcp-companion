package com.github.sinlordep.mcpcompanion.toolsets.structuralsearch

import com.github.sinlordep.mcpcompanion.structuralsearch.MatchedVariable
import com.github.sinlordep.mcpcompanion.structuralsearch.PatternSource
import com.github.sinlordep.mcpcompanion.structuralsearch.SearchTemplate
import com.github.sinlordep.mcpcompanion.structuralsearch.StructuralMatch
import com.github.sinlordep.mcpcompanion.structuralsearch.StructuralSearchOutcome
import com.github.sinlordep.mcpcompanion.toolsets.symbols.OriginOutput
import kotlinx.serialization.Serializable

/**
 * MCP output model of `mc_structural_search`. `search`: [fileType], [template] (when a named template ran), [total]
 * matches and one page of [matches]; `templates`: [total] templates and one page of [templates].
 */
@Serializable
data class StructuralSearchOutput(
    val fileType: String? = null,
    val template: String? = null,
    val total: Int,
    val matches: List<StructuralMatchOutput>? = null,
    val templates: List<SearchTemplateOutput>? = null,
    val hasMore: Boolean,
    val nextOffset: Int? = null,
) {
    companion object {
        fun from(outcome: StructuralSearchOutcome): StructuralSearchOutput = when (outcome) {
            is StructuralSearchOutcome.Matches -> StructuralSearchOutput(
                fileType = outcome.found.fileType,
                template = (outcome.source as? PatternSource.Template)?.name,
                total = outcome.found.total,
                matches = outcome.found.page.items.map(StructuralMatchOutput::from),
                hasMore = outcome.found.page.hasMore,
                nextOffset = outcome.found.page.nextOffset,
            )

            is StructuralSearchOutcome.Templates -> StructuralSearchOutput(
                total = outcome.found.total,
                templates = outcome.found.page.items.map(SearchTemplateOutput::from),
                hasMore = outcome.found.page.hasMore,
                nextOffset = outcome.found.page.nextOffset,
            )
        }
    }
}

/**
 * One match: [path], 1-based [line] and [column] where it starts, [endLine], [lineText] (the start line, trimmed),
 * [enclosing] declaration, [text] of the match ([textTruncated] when cut) and [variables] with the values they matched.
 */
@Serializable
data class StructuralMatchOutput(
    val path: String,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val origin: OriginOutput,
    val lineText: String,
    val enclosing: String? = null,
    val text: String,
    val textTruncated: Boolean = false,
    val variables: List<MatchedVariableOutput>? = null,
) {
    companion object {
        fun from(match: StructuralMatch): StructuralMatchOutput {
            val location = match.site.location
            return StructuralMatchOutput(
                path = location.path,
                line = location.line,
                column = location.column,
                endLine = match.endLine,
                origin = OriginOutput.of(location.origin),
                lineText = match.site.lineText,
                enclosing = match.site.enclosingDeclaration,
                text = match.text,
                textTruncated = match.textTruncated,
                variables = match.variables.map(MatchedVariableOutput::from).ifEmpty { null },
            )
        }
    }
}

/** The values one variable matched; [truncated] when values or their texts were cut. */
@Serializable
data class MatchedVariableOutput(val name: String, val values: List<String>, val truncated: Boolean = false) {
    companion object {
        fun from(variable: MatchedVariable) = MatchedVariableOutput(variable.name, variable.values, variable.truncated)
    }
}

/** One search template; [predefined] for the IDE's built-in ones, false for the user's saved ones. */
@Serializable
data class SearchTemplateOutput(
    val name: String,
    val category: String? = null,
    val fileType: String? = null,
    val pattern: String,
    val predefined: Boolean,
    val variables: List<String>? = null,
) {
    companion object {
        fun from(template: SearchTemplate) = SearchTemplateOutput(
            name = template.name,
            category = template.category,
            fileType = template.fileType,
            pattern = template.pattern,
            predefined = template.predefined,
            variables = template.variables.ifEmpty { null },
        )
    }
}
