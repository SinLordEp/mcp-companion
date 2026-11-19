package com.github.sinlordep.mcpcompanion.structuralsearch.ssr

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.mapNotNull
import com.github.sinlordep.mcpcompanion.common.progress.ide.runUnderIndicator
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.source.SourceKey
import com.github.sinlordep.mcpcompanion.structuralsearch.FoundMatches
import com.github.sinlordep.mcpcompanion.structuralsearch.SearchTemplate
import com.github.sinlordep.mcpcompanion.structuralsearch.StructuralQuery
import com.github.sinlordep.mcpcompanion.structuralsearch.StructuralSearchGateway
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.MatchResult
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.NoMatchFoundException
import com.intellij.structuralsearch.StructuralSearchException

/**
 * The IDE's Structural Search without its dialog or the Find tool window: the template is compiled and checked
 * (`Matcher.validate`, the dialog's own check, whose message is passed on), then `Matcher.findMatches` runs on a pooled
 * thread under an indicator of its own (cancelling the call cancels it) and reads the code in its own read actions.
 * Nothing is opened, shown or focused.
 */
internal class IdeStructuralSearchGateway(
    private val project: Project,
    private val options: SearchOptions,
    private val templates: SearchTemplates,
    private val descriptions: MatchDescriptions,
) : StructuralSearchGateway {

    override suspend fun search(
        query: StructuralQuery,
        limit: Int,
        pick: (List<SourceKey>) -> ToolResult<Page<SourceKey>>,
    ): ToolResult<FoundMatches> {
        val prepared = when (val checked = smartReadAction(project) { options.of(query).checked() }) {
            is ToolResult.Success -> checked.value
            is ToolResult.Failure -> return checked
        }
        val matches = if (prepared.canMatch) {
            when (val found = collect(prepared.options, limit)) {
                is ToolResult.Success -> found.value
                is ToolResult.Failure -> return found
            }
        } else {
            emptyMap()
        }
        val language = prepared.options.fileType?.let(SearchFileTypes::nameOf).orEmpty()
        return smartReadAction(project) {
            pick(matches.keys.toList()).map { page ->
                val described = page.mapNotNull { key -> matches[key]?.let { descriptions.describe(key, it) } }
                FoundMatches(language, matches.size, described)
            }
        }
    }

    override suspend fun templates(): ToolResult<List<SearchTemplate>> =
        ToolResult.success(readAction { templates.all() })

    /**
     * Options whose template compiles; [canMatch] is false when the IDE already knows that nothing in the scope can
     * match (a word of the template is in no file of the scope: `NoMatchFoundException`, which the IDE's dialog shows
     * as a message, but which is an empty answer here).
     */
    private class Prepared(val options: MatchOptions, val canMatch: Boolean)

    /** [this] options when the IDE can compile their template; its message otherwise. */
    private fun ToolResult<MatchOptions>.checked(): ToolResult<Prepared> = when (this) {
        is ToolResult.Failure -> this

        is ToolResult.Success -> try {
            Matcher.validate(project, value)
            ToolResult.success(Prepared(value, canMatch = true))
        } catch (_: NoMatchFoundException) {
            ToolResult.success(Prepared(value, canMatch = false))
        } catch (e: StructuralSearchException) {
            invalidTemplate(e)
        }
    }

    private suspend fun collect(options: MatchOptions, limit: Int): ToolResult<Map<SourceKey, MatchResult>> =
        runUnderIndicator { indicator ->
            val collector = MatchCollector(limit, indicator)
            try {
                val matcher = ApplicationManager.getApplication()
                    .runReadAction(Computable { Matcher(project, options) })
                matcher.findMatches(collector)
                ToolResult.success(collector.matches())
            } catch (e: ProcessCanceledException) {
                if (!collector.isFull()) throw e
                ToolResult.success(collector.matches())
            } catch (_: NoMatchFoundException) {
                ToolResult.success(emptyMap())
            } catch (e: StructuralSearchException) {
                invalidTemplate(e)
            }
        }

    /** The IDE's own message; some compile errors come without one, as in the IDE's dialog ("malformed"). */
    private fun invalidTemplate(e: StructuralSearchException): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.INVALID_ARGUMENT,
        e.message?.takeIf { it.isNotBlank() }?.let { "The IDE cannot use this search template: $it." }
            ?: "The IDE reports the search template as malformed and gives no detail.",
        "Write the template as code of the language (fileType) with variables \$Name\$ for the varying parts, " +
            "e.g. \$Instance\$.equals(\$Arg\$); a statement ends with ';'.",
    )
}
