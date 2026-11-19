package com.github.sinlordep.mcpcompanion.move.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringAnalysis
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringAttempt
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringGateway
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringPlan
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PreparedRefactoring
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PsiRefactoringRunner
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.move.ClassDestination
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile

/**
 * Moves a class with the [ClassMoveSupport] of its language. Checks what does not depend on the language: the class
 * is a writable project declaration, and the target source root is one. Runs through [PsiRefactoringRunner], so
 * there is no dialog.
 */
class PsiClassMoveGateway(
    private val project: Project,
    private val files: ProjectFiles,
    private val runner: PsiRefactoringRunner,
    private val supports: () -> List<ClassMoveSupport> = { ClassMoveSupport.EP.extensionList },
) : RefactoringGateway<ClassDestination> {

    override suspend fun analyse(target: LocatedSymbol, details: ClassDestination): ToolResult<RefactoringAnalysis> =
        runner.analyse {
            runner.writableDeclaration(target.handle).flatMap { declaration ->
                sourceRoot(details.sourceRoot).flatMap { root ->
                    supports().firstNotNullOfOrNull { it.prepare(declaration, details.targetPackage, root) }
                        ?: unsupported()
                }
            }
        }

    override suspend fun apply(plan: RefactoringPlan): ToolResult<RefactoringAttempt> = runner.apply(plan)

    /** The source root a client names, null when none is named. Call inside a read action. */
    private fun sourceRoot(clientPath: String?): ToolResult<VirtualFile?> {
        if (clientPath == null) return ToolResult.success(null)
        val directory = files.find(clientPath)?.takeIf { it.isDirectory }
            ?: return ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "targetSourceRoot $clientPath is not a directory.",
                SOURCE_ROOTS,
            )
        val index = ProjectFileIndex.getInstance(project)
        return if (index.isInSourceContent(directory) && index.getSourceRootForFile(directory) == directory) {
            ToolResult.success(directory)
        } else {
            ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "targetSourceRoot $clientPath is not a source root of the project.",
                SOURCE_ROOTS,
            )
        }
    }

    private fun unsupported(): ToolResult<PreparedRefactoring> = ToolResult.failure(
        ErrorCode.UNSUPPORTED,
        "The IDE cannot move this kind of declaration here; mc_move moves top-level Java classes (Kotlin is not " +
            "supported).",
        "Point at a top-level Java class, or move the code by editing it, or ask the user to run the IDE's Move.",
    )

    private companion object {
        const val SOURCE_ROOTS = "Pass a source or test source folder; mc_modules list shows each module's folders."
    }
}
