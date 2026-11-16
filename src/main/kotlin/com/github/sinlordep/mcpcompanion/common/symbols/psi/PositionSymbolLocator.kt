package com.github.sinlordep.mcpcompanion.common.symbols.psi

import com.github.sinlordep.mcpcompanion.common.paths.ProjectPathRenderer
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocator
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil

/**
 * Finds the symbol at a file position, for any language: the declaration a reference there resolves to, or the
 * declaration whose name sits there. Waits for indexing to finish.
 */
class PositionSymbolLocator(
    private val project: Project,
    private val paths: ProjectPathRenderer,
    private val mapper: PsiSymbolMapper,
) : SymbolLocator<SymbolQuery.AtPosition> {

    override suspend fun locate(query: SymbolQuery.AtPosition): ToolResult<LocatedSymbol> =
        smartReadAction(project) { locateNow(query) }

    private fun locateNow(query: SymbolQuery.AtPosition): ToolResult<LocatedSymbol> {
        val file = findPsiFile(query.path)
            ?: return ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "File not found: ${query.path}",
                "Pass a path relative to the project root, or an absolute path.",
            )
        val offset = offsetOf(file, query.line, query.column)
            ?: return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "Line ${query.line}, column ${query.column} is outside ${query.path}",
                "Lines and columns are 1-based; check them against the current file content.",
            )
        return resolveAt(file, offset)
    }

    private fun resolveAt(file: PsiFile, offset: Int): ToolResult<LocatedSymbol> {
        val reference = file.findReferenceAt(offset)
        if (reference is PsiPolyVariantReference && reference.multiResolve(false).size > 1) {
            return ToolResult.failure(
                ErrorCode.AMBIGUOUS,
                "The reference '${reference.canonicalText}' resolves to several declarations.",
                "Point at one of the declarations instead, or use its qualified name.",
            )
        }
        val target = reference?.resolve() ?: declarationNamedAt(file, offset)
        val symbol = target?.let(mapper::map)
            ?: return ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "No symbol at that position.",
                "Point at a declaration's name or at a reference to it.",
            )
        return ToolResult.success(symbol)
    }

    private fun declarationNamedAt(file: PsiFile, offset: Int): PsiElement? {
        val owner = PsiTreeUtil.getParentOfType(file.findElementAt(offset), PsiNameIdentifierOwner::class.java, false)
        return owner?.takeIf { it.nameIdentifier?.textRange?.containsOffset(offset) == true }
    }

    private fun findPsiFile(path: String): PsiFile? {
        val absolute = paths.resolve(path)
        // Inside the project, look up relative to the project directory, which also works for non-local file
        // systems (e.g. the in-memory one of light tests); anything else is a local file.
        val file: VirtualFile =
            paths.relativize(absolute)?.let { project.guessProjectDir()?.findFileByRelativePath(it) }
                ?: LocalFileSystem.getInstance().findFileByPath(absolute)
                ?: return null
        return if (file.isDirectory) null else PsiManager.getInstance(project).findFile(file)
    }

    private fun offsetOf(file: PsiFile, line: Int, column: Int): Int? {
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        if (line < 1 || line > document.lineCount || column < 1) return null
        val lineStart = document.getLineStartOffset(line - 1)
        val offset = lineStart + column - 1
        return offset.takeIf { it <= document.getLineEndOffset(line - 1) }
    }
}
