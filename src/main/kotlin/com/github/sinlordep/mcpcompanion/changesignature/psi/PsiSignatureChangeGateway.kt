package com.github.sinlordep.mcpcompanion.changesignature.psi

import com.github.sinlordep.mcpcompanion.changesignature.SignatureChange
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringAnalysis
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringAttempt
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringGateway
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringPlan
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PsiRefactoringRunner
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol

/**
 * Changes a signature with the [SignatureChangeSupport] of the declaration's language, after the language-neutral
 * check that it is a writable project declaration. Runs through [PsiRefactoringRunner], so there is no dialog.
 */
class PsiSignatureChangeGateway(
    private val runner: PsiRefactoringRunner,
    private val supports: () -> List<SignatureChangeSupport> = { SignatureChangeSupport.EP.extensionList },
) : RefactoringGateway<SignatureChange> {

    override suspend fun analyse(target: LocatedSymbol, details: SignatureChange): ToolResult<RefactoringAnalysis> =
        runner.analyse {
            runner.writableDeclaration(target.handle).flatMap { declaration ->
                supports().firstNotNullOfOrNull { it.prepare(declaration, details) }
                    ?: ToolResult.failure(
                        ErrorCode.UNSUPPORTED,
                        "The IDE cannot change the signature of this declaration here; mc_change_signature changes " +
                            "Java methods and constructors (Kotlin is not supported).",
                        "Point at a Java method, or edit the code yourself, or ask the user to run the IDE's " +
                            "Change Signature.",
                    )
            }
        }

    override suspend fun apply(plan: RefactoringPlan): ToolResult<RefactoringAttempt> = runner.apply(plan)
}
