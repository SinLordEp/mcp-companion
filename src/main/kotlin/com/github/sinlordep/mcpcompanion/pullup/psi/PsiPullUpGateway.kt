package com.github.sinlordep.mcpcompanion.pullup.psi

import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringAnalysis
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringAttempt
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringGateway
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringPlan
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PsiRefactoringRunner
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.pullup.MemberPullUp

/**
 * Pulls members up with the [PullUpSupport] of their language, after the language-neutral check that the member is a
 * writable project declaration. Runs through [PsiRefactoringRunner], so there is no dialog.
 */
class PsiPullUpGateway(
    private val runner: PsiRefactoringRunner,
    private val supports: () -> List<PullUpSupport> = { PullUpSupport.EP.extensionList },
) : RefactoringGateway<LocatedSymbol, MemberPullUp> {

    override suspend fun analyse(target: LocatedSymbol, details: MemberPullUp): ToolResult<RefactoringAnalysis> =
        runner.analyse {
            runner.writableDeclaration(target.handle).flatMap { declaration ->
                supports().firstNotNullOfOrNull { it.prepare(declaration, details) }
                    ?: ToolResult.failure(
                        ErrorCode.UNSUPPORTED,
                        "The IDE cannot pull up this declaration here; mc_pull_up moves fields, methods and nested " +
                            "classes of Java classes (Kotlin is not supported).",
                        "Point at a member of a Java class, or edit the code yourself, or ask the user to run the " +
                            "IDE's Pull Members Up.",
                    )
            }
        }

    override suspend fun apply(plan: RefactoringPlan): ToolResult<RefactoringAttempt> = runner.apply(plan)
}
