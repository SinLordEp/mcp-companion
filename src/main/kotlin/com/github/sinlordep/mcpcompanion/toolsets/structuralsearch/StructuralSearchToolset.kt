package com.github.sinlordep.mcpcompanion.toolsets.structuralsearch

import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.structuralsearch.StructuralSearchArguments
import com.github.sinlordep.mcpcompanion.structuralsearch.ssr.structuralSearchControlFor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.symbols.ToolParameters
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/**
 * MCP adapter for `mc_structural_search`. Registered in `mcp-companion-structural-search.xml`: it needs the IDE's
 * Structural Search plugin.
 */
class StructuralSearchToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_structural_search")
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
        openWorldHint = McpToolHintValue.FALSE,
    )
    @McpDescription(description = DESCRIPTION)
    suspend fun structuralSearch(
        @McpDescription(StructuralSearchParameterTexts.ACTION)
        action: StructuralSearchActionInput = StructuralSearchActionInput.SEARCH,
        @McpDescription(StructuralSearchParameterTexts.PATTERN) pattern: String? = null,
        @McpDescription(StructuralSearchParameterTexts.TEMPLATE) template: String? = null,
        @McpDescription(StructuralSearchParameterTexts.FILE_TYPE) fileType: String? = null,
        @McpDescription(StructuralSearchParameterTexts.SCOPE) scope: String? = null,
        @McpDescription(StructuralSearchParameterTexts.CONSTRAINTS) constraints: List<VariableConstraintInput>? = null,
        @McpDescription(StructuralSearchParameterTexts.CASE_SENSITIVE) caseSensitive: Boolean? = null,
        @McpDescription(StructuralSearchParameterTexts.QUERY) query: String? = null,
        @McpDescription(StructuralSearchParameterTexts.LIMIT) limit: Int? = null,
        @McpDescription(ToolParameters.OFFSET) offset: Int? = null,
    ): StructuralSearchOutput {
        val project = currentCoroutineContext().project
        val arguments = StructuralSearchArguments(
            pattern = pattern,
            template = template,
            fileType = fileType,
            scope = scope,
            constraints = constraints?.map(VariableConstraintInput::toConstraint),
            caseSensitive = caseSensitive,
            query = query,
            limit = limit,
            offset = offset,
        )
        return structuralSearchControlFor(project).execute(action.action, arguments)
            .map(StructuralSearchOutput::from)
            .toMcpOutput()
    }

    companion object {
        const val ID: String = "structural_search"

        private const val DESCRIPTION: String = "The IDE's Structural Search: finds code by its syntax tree, not " +
            "its text, so formatting and comments do not matter (unlike search_text/search_regex, which " +
            "match characters, and search_symbol, which matches declaration names). Read-only; never replaces. " +
            "action=search (default): pattern is code of the language fileType (default Java; others as " +
            "installed, e.g. Kotlin, XML, HTML) where \$Name\$ variables match any element of their place: " +
            "\$Instance\$.equals(\$Arg\$) finds every equals call, \"try { \$Body\$; } catch (\$Type\$ \$e\$) " +
            "{}\" empty catch blocks (comments do not count), \"class \$C\$ { \$T\$ \$m\$(\$PT\$ \$P\$); }\" " +
            "methods with any body (a member needs its class around it; constrain m target=true to report the " +
            "methods, PT and P minCount=0 maxCount=-1 for any parameters). A variable matches one element unless " +
            "a constraint says otherwise. constraints " +
            "refine variables by name (without \$): text (Java regex the matched text must match; " +
            "textInverted=true: must not), type (expression type names separated by |, e.g. String|Integer; " +
            "withinHierarchy=true also subtypes; typeInverted), minCount/maxCount (how many elements, e.g. " +
            "minCount=0 maxCount=-1 for any number of arguments; -1 = no limit), target=true (report that " +
            "variable's element as the match instead of the whole pattern). Or template=<name> runs a built-in or " +
            "saved template (action=templates lists them with their patterns), optionally with extra " +
            "constraints. scope: project (default), project_and_libraries, module:<name>, dir:<path>, " +
            "file:<path>. Each match: path (as read_file takes it), line, column, endLine, lineText, enclosing " +
            "declaration, text (cut at 400 chars) and the variables inside the match with the values they matched " +
            "(cut at 20 values of 200 chars). Sorted by path and offset; limit/offset page (default 50, max 1000; " +
            "nextOffset), " +
            "total counts all matches; more than 10000 matches: narrow the scope. A template the IDE cannot " +
            "compile: INVALID_ARGUMENT with the IDE's message. action=templates: the templates (name, category, " +
            "fileType, pattern, predefined, constrained variables), filtered by query (part of name or category) " +
            "and fileType. Template names and the IDE's messages are in the IDE's language."
    }
}

/** Parameter descriptions of `mc_structural_search`: prompts for the agent, so short. */
private object StructuralSearchParameterTexts {
    const val ACTION = "search (default) or templates."
    const val PATTERN = "search: the code to find, with \$Name\$ variables, e.g. \$Instance\$.equals(\$Arg\$)."
    const val TEMPLATE = "search: instead of pattern, the name of a template as action=templates lists it."
    const val FILE_TYPE = "Language of the pattern, e.g. Java (default), Kotlin, XML; templates: only this language."
    const val SCOPE = "search: project (default), project_and_libraries, module:<name>, dir:<path> or file:<path>."
    const val CONSTRAINTS = "search: [{name, text, textInverted, type, typeInverted, withinHierarchy, minCount, " +
        "maxCount, target}] per variable; name without \$."
    const val CASE_SENSITIVE = "search: match names and text case-sensitively. Default: the template's setting."
    const val QUERY = "templates: only templates whose name or category contains this text."
    const val LIMIT = "Page size, 1-1000. Default 50."
}
