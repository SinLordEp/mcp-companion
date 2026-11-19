package com.github.sinlordep.mcpcompanion.structure.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.structure.FileOutline
import com.github.sinlordep.mcpcompanion.structure.StructureGateway
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

/**
 * The IDE's Structure view model of a file, built without an editor and without opening one: the language's
 * `StructureViewBuilder` (`LanguageStructureViewBuilder`), its tree model (`TreeBasedStructureViewBuilder
 * .createStructureViewModel(null)`), walked from the root ([OutlineWalker]). Works for every language with a tree
 * Structure view (Java, Kotlin, XML, JSON, YAML, Markdown, ...), library classes included (their decompiled text).
 * The model's sorters, filters and node providers (inherited members, anonymous classes, lambdas) are not applied: the
 * elements come in source order, all of them.
 */
internal class PsiStructureGateway(
    private val project: Project,
    private val files: ProjectFiles,
    private val facts: () -> List<DeclarationFactsProvider>,
) : StructureGateway {

    override suspend fun outline(path: String, maxElements: Int): ToolResult<FileOutline> {
        val file = readAction { files.find(path) } ?: return ToolResult.failure(
            ErrorCode.NOT_FOUND,
            "No file at $path.",
            "Pass a path relative to the project root or an absolute path; search_file finds files by name.",
        )
        if (file.isDirectory) {
            return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "$path is a directory; mc_file_structure shows the structure of one file.",
                "Use list_directory_tree for directories.",
            )
        }
        // Waits for the IDE's own commit of edited documents instead of committing on the EDT, which a modal dialog
        // (e.g. the VCS asking whether to add a new file) would block.
        val constraints = arrayOf(ReadConstraint.withDocumentsCommitted(project), ReadConstraint.inSmartMode(project))
        return constrainedReadAction(*constraints) { outlineOf(file, maxElements) }
    }

    private fun outlineOf(file: VirtualFile, maxElements: Int): ToolResult<FileOutline> {
        val path = files.pathOf(file)
        val psiFile = PsiManager.getInstance(project).findFile(file)
            ?: return unsupported("$path is not a text or source file the IDE can parse.")
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psiFile)
            ?: return unsupported("The IDE has no Structure view for ${file.fileType.name} files ($path).")
        if (builder !is TreeBasedStructureViewBuilder) {
            return unsupported("The Structure view for ${file.fileType.name} files is not a tree model ($path).")
        }
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: FileDocumentManager.getInstance().getDocument(file)
        val model = builder.createStructureViewModel(null)
        return try {
            val walker = OutlineWalker(file, document, facts(), maxElements)
            val elements = walker.children(model.root)
            ToolResult.success(FileOutline(path, psiFile.language.displayName, elements, walker.complete))
        } finally {
            Disposer.dispose(model)
        }
    }

    private fun unsupported(message: String): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.UNSUPPORTED,
        message,
        "Read the file with read_file instead.",
    )
}
