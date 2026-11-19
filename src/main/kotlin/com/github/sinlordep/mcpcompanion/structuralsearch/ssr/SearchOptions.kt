package com.github.sinlordep.mcpcompanion.structuralsearch.ssr

import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.scope.psi.PsiSearchScopes
import com.github.sinlordep.mcpcompanion.structuralsearch.PatternSource
import com.github.sinlordep.mcpcompanion.structuralsearch.StructuralQuery
import com.github.sinlordep.mcpcompanion.structuralsearch.TemplateVariables
import com.github.sinlordep.mcpcompanion.structuralsearch.VariableConstraint
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.MatchVariableConstraint

/**
 * Builds the Structural Search options of a query: the template (written, or a copy of a named template's options),
 * its language, the search scope, case sensitivity and the client's variable constraints on top of the template's
 * own. Call inside a read action.
 */
internal class SearchOptions(private val templates: SearchTemplates, private val scopes: PsiSearchScopes) {

    fun of(query: StructuralQuery): ToolResult<MatchOptions> = baseOptions(query).flatMap { options ->
        TemplateVariables.checkConstrained(options.searchPattern, query.constraints).flatMap {
            scopes.resolve(query.scope).map { scope ->
                options.scope = scope
                query.caseSensitive?.let { options.isCaseSensitiveMatch = it }
                query.constraints.forEach { constraint ->
                    val variable = options.getVariableConstraint(constraint.name)
                        ?: options.addNewVariableConstraint(constraint.name)
                    constraint.applyTo(variable)
                }
                options
            }
        }
    }

    private fun baseOptions(query: StructuralQuery): ToolResult<MatchOptions> = when (val source = query.source) {
        is PatternSource.Text -> SearchFileTypes.resolve(query.fileType).map { fileType ->
            MatchOptions().apply {
                setFileType(fileType)
                searchPattern = source.pattern
            }
        }

        is PatternSource.Template -> {
            val fileType = query.fileType?.let { name ->
                when (val resolved = SearchFileTypes.resolve(name)) {
                    is ToolResult.Success -> resolved.value
                    is ToolResult.Failure -> return resolved
                }
            }
            templates.find(source.name, fileType).map { it.matchOptions.copy() }
        }
    }

    private fun VariableConstraint.applyTo(variable: MatchVariableConstraint) {
        text?.let { variable.regExp = it }
        textInverted?.let { variable.isInvertRegExp = it }
        type?.let { variable.expressionTypes = it }
        typeInverted?.let { variable.isInvertExprType = it }
        withinHierarchy?.let { variable.isExprTypeWithinHierarchy = it }
        minCount?.let { variable.minCount = it }
        maxCount?.let { variable.maxCount = it }
        target?.let { variable.isPartOfSearchResults = it }
    }
}
