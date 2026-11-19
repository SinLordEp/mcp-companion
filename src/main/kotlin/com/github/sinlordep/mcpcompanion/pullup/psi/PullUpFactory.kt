package com.github.sinlordep.mcpcompanion.pullup.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PsiRefactoringRunner
import com.github.sinlordep.mcpcompanion.common.source.psi.PsiCodeSites
import com.github.sinlordep.mcpcompanion.common.symbols.psi.symbolLocatorsFor
import com.github.sinlordep.mcpcompanion.pullup.PullUpMembers
import com.intellij.openapi.project.Project

/** Wires [PullUpMembers] to the platform for [project]. Cheap; called once per tool call. */
fun pullUpMembersFor(project: Project): PullUpMembers {
    val files = ProjectFiles.of(project)
    val runner = PsiRefactoringRunner(project, files, PsiCodeSites(project, files))
    return PullUpMembers(symbolLocatorsFor(project, files), PsiPullUpGateway(runner))
}
