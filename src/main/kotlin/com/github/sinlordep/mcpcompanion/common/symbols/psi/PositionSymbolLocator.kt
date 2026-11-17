package com.github.sinlordep.mcpcompanion.common.symbols.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocator
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
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
    private val files: ProjectFiles,
    private val mapper: PsiSymbolMapper,
) : SymbolLocator<SymbolQuery.AtPosition> {

    override suspend fun locate(query: SymbolQuery.AtPosition): ToolResult<LocatedSymbol> =
        smartReadAction(project) { locateNow(query) }

    private fun locateNow(query: SymbolQuery.AtPosition): ToolResult<LocatedSymbol> {
        val virtualFile = files.find(query.path)?.takeUnless { it.isDirectory }
            ?: return ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "File not found: ${query.path}",
                "Pass a path relative to the project root (.. allowed), an absolute path, an archive entry " +
                    "(/path/x.jar!/pkg/Foo.java) or a file:// or jar:// URL.",
            )
        if (files.originOf(virtualFile) == FileOrigin.OTHER) {
            return ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "${query.path} is neither in the project nor in a library or SDK of it.",
                "Pass a file of the project's content roots, libraries or SDK.",
            )
        }
        val file = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return ToolResult.failure(ErrorCode.UNSUPPORTED, "${query.path} is not a source file the IDE can parse.")
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

    private fun offsetOf(file: PsiFile, line: Int, column: Int): Int? {
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        if (line < 1 || line > document.lineCount || column < 1) return null
        val lineStart = document.getLineStartOffset(line - 1)
        val offset = lineStart + column - 1
        return offset.takeIf { it <= document.getLineEndOffset(line - 1) }
    }
}
