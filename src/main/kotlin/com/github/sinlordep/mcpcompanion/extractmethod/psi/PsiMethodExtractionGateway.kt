package com.github.sinlordep.mcpcompanion.extractmethod.psi

import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringAnalysis
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringAttempt
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringGateway
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringPlan
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PreparedRefactoring
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PsiRefactoringRunner
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.source.SourceRange
import com.github.sinlordep.mcpcompanion.common.source.psi.textRangeOf
import com.github.sinlordep.mcpcompanion.extractmethod.MethodExtraction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Extracts a method with the [MethodExtractionSupport] of the file's language, after the language-neutral checks:
 * the file is a writable project file and the range lies in it. Runs through [PsiRefactoringRunner], so there is no
 * dialog.
 */
class PsiMethodExtractionGateway(
    private val runner: PsiRefactoringRunner,
    private val supports: () -> List<MethodExtractionSupport> = { MethodExtractionSupport.EP.extensionList },
) : RefactoringGateway<SourceRange, MethodExtraction> {

    override suspend fun analyse(target: SourceRange, details: MethodExtraction): ToolResult<RefactoringAnalysis> =
        runner.analyse {
            runner.writableFile(target.path).flatMap { file -> prepare(file, target, details) }
        }

    override suspend fun apply(plan: RefactoringPlan): ToolResult<RefactoringAttempt> = runner.apply(plan)

    private fun prepare(
        file: PsiFile,
        target: SourceRange,
        details: MethodExtraction,
    ): ToolResult<PreparedRefactoring> {
        val range = PsiDocumentManager.getInstance(file.project).getDocument(file)?.textRangeOf(target)
            ?: return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "The range reaches outside ${target.path} (it has fewer lines, or a line fewer columns).",
                "Lines and columns are 1-based; check them against the current file content.",
            )
        return supports().firstNotNullOfOrNull { it.prepare(file, range, details) }
            ?: ToolResult.failure(
                ErrorCode.UNSUPPORTED,
                "The IDE cannot extract a method from this file here; mc_extract_method works on Java files " +
                    "(Kotlin is not supported).",
                "Edit the code yourself, or ask the user to run the IDE's Extract Method.",
            )
    }
}
