package com.github.sinlordep.mcpcompanion.move.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PsiRefactoringRunner
import com.github.sinlordep.mcpcompanion.common.source.psi.PsiCodeSites
import com.github.sinlordep.mcpcompanion.common.symbols.psi.symbolLocatorsFor
import com.github.sinlordep.mcpcompanion.move.MoveClass
import com.intellij.openapi.project.Project

/** Wires [MoveClass] to the platform for [project]. Cheap; called once per tool call. */
fun moveClassFor(project: Project): MoveClass {
    val files = ProjectFiles.of(project)
    val runner = PsiRefactoringRunner(project, files, PsiCodeSites(project, files))
    return MoveClass(symbolLocatorsFor(project, files), PsiClassMoveGateway(project, files, runner))
}
