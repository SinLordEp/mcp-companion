package com.github.sinlordep.mcpcompanion.structuralsearch

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.source.CodeSite
import com.github.sinlordep.mcpcompanion.common.source.SourceKey

/** Where the search template comes from: written by the client, or a template the IDE knows by name. */
sealed interface PatternSource {
    data class Text(val pattern: String) : PatternSource

    data class Template(val name: String) : PatternSource
}

/**
 * A constraint on one template variable (`$Name$`), as the IDE's Structural Search dialog edits it. Null fields keep
 * the template's own setting. [text] is a regular expression the matched text must (or with [textInverted] must not)
 * match; [type] one or more type names separated by `|` that a matched expression must have (subtypes too when
 * [withinHierarchy]); [minCount]..[maxCount] how many elements the variable matches ([UNBOUNDED] for no upper limit);
 * [target] makes the variable's match the reported match instead of the whole template.
 */
data class VariableConstraint(
    val name: String,
    val text: String? = null,
    val textInverted: Boolean? = null,
    val type: String? = null,
    val typeInverted: Boolean? = null,
    val withinHierarchy: Boolean? = null,
    val minCount: Int? = null,
    val maxCount: Int? = null,
    val target: Boolean? = null,
) {
    companion object {
        const val UNBOUNDED: Int = Int.MAX_VALUE
    }
}

/**
 * A validated search: the template ([source]) in the language of [fileType] (null: the IDE's default, Java when
 * present), in [scope], with extra [constraints] on its variables and, when not null, [caseSensitive] matching.
 */
data class StructuralQuery(
    val source: PatternSource,
    val fileType: String?,
    val scope: SearchScopeSpec,
    val constraints: List<VariableConstraint>,
    val caseSensitive: Boolean?,
)

/** The values one template variable matched in one match, in source order; [truncated] when values were cut. */
data class MatchedVariable(val name: String, val values: List<String>, val truncated: Boolean)

/**
 * One match: where it starts ([site]: path, line, column, the line's text, enclosing declaration), its last line,
 * its text (cut to a maximum; [textTruncated]) and the values of the template's variables.
 */
data class StructuralMatch(
    val site: CodeSite,
    val endLine: Int,
    val text: String,
    val textTruncated: Boolean,
    val variables: List<MatchedVariable>,
)

/** The answer of a search: the language the template was read in, all [total] matches and one [page] of them. */
data class FoundMatches(val fileType: String, val total: Int, val page: Page<StructuralMatch>)

/**
 * A search template the IDE offers: built in ([predefined]) or saved by the user. [fileType] is the language of its
 * template; [variables] are the variables the template constrains.
 */
data class SearchTemplate(
    val name: String,
    val category: String?,
    val fileType: String?,
    val pattern: String,
    val predefined: Boolean,
    val variables: List<String>,
)

/** The answer of `templates`: [total] templates that pass the filters and one [page] of them. */
data class FoundTemplates(val total: Int, val page: Page<SearchTemplate>)

/** Gateway boundary: the IDE's Structural Search. */
interface StructuralSearchGateway {

    /**
     * Runs [query], stopping after [limit] matches, lets [pick] choose one page of the match keys (in no particular
     * order) and describes only the picked matches. A template the IDE cannot compile is an `INVALID_ARGUMENT` with
     * the IDE's message.
     */
    suspend fun search(
        query: StructuralQuery,
        limit: Int,
        pick: (List<SourceKey>) -> ToolResult<Page<SourceKey>>,
    ): ToolResult<FoundMatches>

    /** Every search template the IDE offers: its built-in ones and those the user saved (IDE and project level). */
    suspend fun templates(): ToolResult<List<SearchTemplate>>
}

/** What `templates` keeps: [query] in the name or category (ignoring case) and the language [fileType]. */
data class TemplateFilter(val query: String?, val fileType: String?, val page: PageRequest)
