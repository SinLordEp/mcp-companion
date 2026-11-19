package com.github.sinlordep.mcpcompanion.structuralsearch.ssr

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.structuralsearch.SearchTemplate
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.structuralsearch.plugin.ui.Configuration
import com.intellij.structuralsearch.plugin.ui.ConfigurationManager
import com.intellij.structuralsearch.plugin.ui.SearchConfiguration

/**
 * The search templates of the IDE's Structural Search dialog (`ConfigurationManager.getAllConfigurations`): the
 * built-in ones and the user's saved ones (IDE and project level). Replace templates are left out: this tool only
 * searches. Names are the dialog's, in the IDE's language.
 */
internal class SearchTemplates(private val project: Project) {

    private class Entry(val configuration: Configuration, val predefined: Boolean)

    private fun entries(): List<Entry> = ConfigurationManager.getInstance(project).allConfigurations
        .filterIsInstance<SearchConfiguration>()
        .map { Entry(it, predefined = it.isPredefined) }

    fun all(): List<SearchTemplate> = entries().map { entry ->
        val configuration = entry.configuration
        val options = configuration.matchOptions
        SearchTemplate(
            name = configuration.name,
            category = configuration.category.takeIf { it.isNotBlank() },
            fileType = options.fileType?.let(SearchFileTypes::nameOf),
            pattern = options.searchPattern,
            predefined = entry.predefined,
            variables = options.variableConstraintNames.filterNot { it == Configuration.CONTEXT_VAR_NAME }.sorted(),
        )
    }

    /**
     * The template called [name] (exact, else ignoring case), in [fileType] when given; a saved template hides a
     * built-in one of the same name.
     */
    fun find(name: String, fileType: LanguageFileType?): ToolResult<Configuration> {
        val inLanguage = entries().filter { fileType == null || it.configuration.matchOptions.fileType == fileType }
        val named = inLanguage.filter { it.configuration.name == name }
            .ifEmpty { inLanguage.filter { it.configuration.name.equals(name, ignoreCase = true) } }
        val candidates = named.filterNot { it.predefined }.ifEmpty { named }
        return when (candidates.size) {
            0 -> ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "No search template named '$name'${fileType?.let { " for ${SearchFileTypes.nameOf(it)}" }.orEmpty()}.",
                "action=templates lists the templates (query=<part of the name> narrows the list).",
            )

            1 -> ToolResult.success(candidates.single().configuration)

            else -> ToolResult.failure(
                ErrorCode.AMBIGUOUS,
                "Several search templates are named '$name': in " +
                    candidates.mapNotNull { it.configuration.matchOptions.fileType?.let(SearchFileTypes::nameOf) }
                        .distinct().joinToString() + ".",
                "Pass fileType to choose one.",
            )
        }
    }
}
