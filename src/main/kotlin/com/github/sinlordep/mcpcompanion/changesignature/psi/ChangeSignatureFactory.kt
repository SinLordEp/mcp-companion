package com.github.sinlordep.mcpcompanion.changesignature.psi

import com.github.sinlordep.mcpcompanion.changesignature.ChangeSignature
import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PsiRefactoringRunner
import com.github.sinlordep.mcpcompanion.common.source.psi.PsiCodeSites
import com.github.sinlordep.mcpcompanion.common.symbols.psi.symbolLocatorsFor
import com.intellij.openapi.project.Project

/** Wires [ChangeSignature] to the platform for [project]. Cheap; called once per tool call. */
fun changeSignatureFor(project: Project): ChangeSignature {
    val files = ProjectFiles.of(project)
    val runner = PsiRefactoringRunner(project, files, PsiCodeSites(project, files))
    return ChangeSignature(symbolLocatorsFor(project, files), PsiSignatureChangeGateway(runner))
}
