package com.github.sinlordep.mcpcompanion.diagnostics.ide

import com.github.sinlordep.mcpcompanion.common.modulegraph.ProjectGraph
import com.github.sinlordep.mcpcompanion.common.modulegraph.ide.IdeModuleGraph
import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.time.SystemClock
import com.github.sinlordep.mcpcompanion.diagnostics.CollectDiagnostics
import com.github.sinlordep.mcpcompanion.diagnostics.DiagnosticsEnvironment
import com.github.sinlordep.mcpcompanion.diagnostics.FileAnalyzer
import com.github.sinlordep.mcpcompanion.diagnostics.FileSelection
import com.github.sinlordep.mcpcompanion.diagnostics.SourceFile
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.observation.Observation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Wires `mc_diagnostics` to the platform for [project]. Cheap; called once per tool call. */
fun diagnosticsFor(project: Project): CollectDiagnostics =
    CollectDiagnostics(IdeDiagnosticsEnvironment(project), SystemClock)

/**
 * Analyses files for `mc_diagnostics`. Registered from an optional descriptor (`mcp-companion-vcs.xml`: the batch
 * analysis API lives in the platform's VCS support). Without it the extension point is empty and the tool answers
 * `UNSUPPORTED`.
 */
interface FileAnalyzerProvider {

    fun analyzerFor(project: Project): FileAnalyzer

    companion object {
        val EP: ExtensionPointName<FileAnalyzerProvider> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.fileAnalyzer")
    }
}

/**
 * [DiagnosticsEnvironment] over the project model and the file index, read in one cancellable read action per call.
 * Module scope: the module's source content (sources, tests, resources, generated ones included); directory scope:
 * project content under the directory. Excluded and binary files are never listed.
 */
internal class IdeDiagnosticsEnvironment(private val project: Project) : DiagnosticsEnvironment {

    override suspend fun moduleGraph(): ProjectGraph = IdeModuleGraph(project).read(includeLibraries = false)

    override fun analyzer(): FileAnalyzer? = FileAnalyzerProvider.EP.extensionList.firstOrNull()?.analyzerFor(project)

    /**
     * As `mc_sync waitForIndexing` waits: the platform's configuration activity (a Gradle or Maven import registers
     * itself) must be over, then a smart read action runs. Before an import the module has no source roots, and the
     * analysis of its files would report nothing (seen end to end right after the IDE opened the Gradle fixture).
     */
    override suspend fun awaitIndexes() {
        Observation.awaitConfiguration(project)
        smartReadAction(project) { }
    }

    override suspend fun filesOf(selection: FileSelection): ToolResult<List<SourceFile>> {
        if (selection is FileSelection.Files) refresh(selection.paths)
        return list(selection)
    }

    /** Named files written outside the IDE (not yet in its file system view) are refreshed first, off any lock. */
    private suspend fun refresh(paths: List<String>) = withContext(Dispatchers.IO) {
        val resolver = ProjectFiles.of(project).paths
        val files = LocalFileSystem.getInstance()
        paths.forEach { path -> files.refreshAndFindFileByPath(resolver.resolve(path)) }
    }

    private suspend fun list(selection: FileSelection): ToolResult<List<SourceFile>> = readAction {
        val files = ProjectFiles.of(project)
        val index = ProjectFileIndex.getInstance(project)
        val found = when (selection) {
            is FileSelection.Modules -> ToolResult.success(moduleFiles(selection.names, index))
            is FileSelection.Directory -> directoryFiles(selection.path, files, index)
            is FileSelection.Files -> namedFiles(selection.paths, files)
        }
        when (found) {
            is ToolResult.Failure -> found

            is ToolResult.Success -> ToolResult.success(
                found.value.map { SourceFile(files.pathOf(it), index.getModuleForFile(it)?.name) }
                    .distinctBy { it.path }
                    .sortedBy { it.path },
            )
        }
    }

    private fun moduleFiles(names: List<String>, index: ProjectFileIndex): List<VirtualFile> {
        val manager = ModuleManager.getInstance(project)
        val result = ArrayList<VirtualFile>()
        for (module in names.mapNotNull(manager::findModuleByName)) {
            ModuleRootManager.getInstance(module).fileIndex.iterateContent { file ->
                ProgressManager.checkCanceled()
                if (isSource(file) && index.isInSourceContent(file)) result += file
                true
            }
        }
        return result
    }

    private fun directoryFiles(
        path: String,
        files: ProjectFiles,
        index: ProjectFileIndex,
    ): ToolResult<List<VirtualFile>> {
        val directory = files.find(path)?.takeIf { it.isDirectory }
            ?: return ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "No directory at '$path'.",
                "Pass a directory path relative to the project root, or an absolute one.",
            )
        val result = ArrayList<VirtualFile>()
        index.iterateContentUnderDirectory(directory) { file ->
            ProgressManager.checkCanceled()
            if (isSource(file)) result += file
            true
        }
        return ToolResult.success(result)
    }

    private fun namedFiles(paths: List<String>, files: ProjectFiles): ToolResult<List<VirtualFile>> {
        val resolved = paths.associateWith { path -> files.find(path)?.takeUnless { it.isDirectory } }
        val missing = resolved.filterValues { it == null }.keys
        return if (missing.isEmpty()) {
            ToolResult.success(resolved.values.filterNotNull())
        } else {
            ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "No file at ${missing.take(MAX_NAMED).joinToString { "'$it'" }}" +
                    if (missing.size > MAX_NAMED) " and ${missing.size - MAX_NAMED} more." else ".",
                "Pass file paths relative to the project root, or absolute ones; use directory for a directory.",
            )
        }
    }

    private fun isSource(file: VirtualFile): Boolean = !file.isDirectory && !file.fileType.isBinary

    private companion object {
        const val MAX_NAMED = 10
    }
}
