package com.github.sinlordep.mcpcompanion.run.ide

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolFailures
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocators
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.github.sinlordep.mcpcompanion.common.symbols.psi.psiElement
import com.github.sinlordep.mcpcompanion.run.CodeConfiguration
import com.github.sinlordep.mcpcompanion.run.CodeConfigurationHandle
import com.github.sinlordep.mcpcompanion.run.CodeLocation
import com.github.sinlordep.mcpcompanion.run.CodeRunGateway
import com.github.sinlordep.mcpcompanion.run.ConfigurationRef
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Run configurations from code, as the IDE's Run gutter icon and context Run make them: a `ConfigurationContext` for
 * the PSI element at the location asks every `RunConfigurationProducer` (JUnit, TestNG, Gradle, Application, Kotlin,
 * ...) and keeps the preferred ones (`getConfigurationsFromContext`: an existing matching configuration is reused).
 *
 * What the IDE's action does and this gateway does not: the popup that lets the user choose among several
 * configurations (the candidates are returned instead), the producers' `onFirstRun` (where some ask, e.g. which
 * subclass of an abstract test class or which of several Gradle test tasks to run; [CodeRunGuard]s refuse the cases
 * where skipping it would run the wrong thing), and selecting the new configuration in the toolbar (the user's
 * selection stays). A new configuration is added as a temporary one, so the IDE's rules for temporary configurations
 * apply: it is not shared, and the oldest one goes when the limit of recent temporary configurations is reached.
 */
internal class IdeCodeRunGateway(
    private val project: Project,
    private val files: ProjectFiles,
    private val symbols: SymbolLocators,
    private val guards: () -> List<CodeRunGuard>,
) : CodeRunGateway {

    private class SettingsHandle(val settings: RunnerAndConfigurationSettings) : CodeConfigurationHandle

    override suspend fun configurationsAt(location: CodeLocation): ToolResult<List<CodeConfiguration>> {
        val element = when (val found = elementAt(location)) {
            is ToolResult.Success -> found.value
            is ToolResult.Failure -> return found
        }
        return smartReadAction(project) {
            val target = element.takeIf { it.isValid } ?: return@smartReadAction SymbolFailures.gone()
            val existing = RunManager.getInstance(project).allSettings
            val candidates = ConfigurationContext(target).configurationsFromContext.orEmpty().map { fromContext ->
                val settings = fromContext.configurationSettings
                val source = fromContext.sourceElement
                CodeConfiguration(
                    configuration = ProcessDescriptions.configurationOf(settings),
                    existing = existing.any { it === settings },
                    refusal = guards().firstNotNullOfOrNull { it.refusal(source) },
                    handle = SettingsHandle(settings),
                )
            }
            ToolResult.success(candidates)
        }
    }

    override suspend fun register(candidate: CodeConfiguration): ToolResult<ConfigurationRef> {
        val settings = (candidate.handle as SettingsHandle).settings
        return withContext(Dispatchers.EDT) {
            if (!candidate.existing) {
                settings.isTemporary = true
                RunManager.getInstance(project).addConfiguration(settings)
            }
            ToolResult.success(ProcessDescriptions.configurationOf(settings))
        }
    }

    override suspend fun unregister(configuration: ConfigurationRef) {
        withContext(Dispatchers.EDT) {
            val manager = RunManager.getInstance(project)
            manager.allSettings.find { it.uniqueID == configuration.key && it.isTemporary }
                ?.let(manager::removeConfiguration)
        }
    }

    private suspend fun elementAt(location: CodeLocation): ToolResult<PsiElement> = when (location) {
        is CodeLocation.InFile -> smartReadAction(project) { elementInFile(location) }

        is CodeLocation.ByQualifiedName -> when (
            val symbol = symbols.locate(
                SymbolQuery.ByQualifiedName(location.qualifiedName),
            )
        ) {
            is ToolResult.Failure -> symbol

            is ToolResult.Success -> readAction {
                val element = symbol.value.handle.psiElement()?.navigationElement
                when {
                    element == null -> SymbolFailures.gone()
                    !inProject(element.containingFile) -> notInProject(location.text)
                    else -> ToolResult.success(element)
                }
            }
        }
    }

    private fun elementInFile(location: CodeLocation.InFile): ToolResult<PsiElement> {
        val file = files.find(location.path)
        return when {
            file == null -> ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "File or directory not found: ${location.path}",
                "Pass a path relative to the project root (.. allowed) or an absolute path.",
            )

            files.originOf(file) != FileOrigin.PROJECT -> notInProject(location.text)

            file.isDirectory -> directoryAt(file, location)

            else -> codeInFile(file, location)
        }
    }

    private fun directoryAt(directory: VirtualFile, location: CodeLocation.InFile): ToolResult<PsiElement> {
        if (location.line != null) {
            return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "${location.path} is a directory; line applies to files only.",
                "Omit line to run the tests under the directory.",
            )
        }
        return PsiManager.getInstance(project).findDirectory(directory)?.let { ToolResult.success(it) }
            ?: notInProject(location.text)
    }

    private fun codeInFile(file: VirtualFile, location: CodeLocation.InFile): ToolResult<PsiElement> {
        val psiFile = PsiManager.getInstance(project).findFile(file)
            ?: return ToolResult.failure(ErrorCode.UNSUPPORTED, "${location.path} is not a file the IDE can parse.")
        val line = location.line ?: return ToolResult.success(psiFile)
        return elementAtLine(psiFile, line, location.column)?.let { ToolResult.success(it) } ?: ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "Line $line${location.column?.let { ", column $it" }.orEmpty()} is outside ${location.path}.",
            "Lines and columns are 1-based; check them against the current file content.",
        )
    }

    /** The element at [column] of [line], or at the line's first non-blank character, as the gutter icon's line. */
    private fun elementAtLine(file: PsiFile, line: Int, column: Int?): PsiElement? {
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        if (line > document.lineCount) return null
        val start = document.getLineStartOffset(line - 1)
        val end = document.getLineEndOffset(line - 1)
        val text = document.charsSequence
        val offset = if (column != null) {
            (start + column - 1).takeIf { it <= end } ?: return null
        } else {
            (start until end).firstOrNull { !StringUtil.isWhiteSpace(text[it]) } ?: start
        }
        return file.findElementAt(offset) ?: file
    }

    private fun inProject(file: PsiFile?): Boolean {
        val virtualFile = file?.virtualFile ?: return false
        return files.originOf(virtualFile) == FileOrigin.PROJECT
    }

    private fun notInProject(location: String): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.INVALID_ARGUMENT,
        "$location is not in the project's own content (a library, the SDK or outside the project); only project " +
            "code is run from a location.",
        "Point at a test or main function in the project's sources.",
    )
}
