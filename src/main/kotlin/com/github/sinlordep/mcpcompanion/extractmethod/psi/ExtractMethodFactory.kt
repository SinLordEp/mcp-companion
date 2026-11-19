package com.github.sinlordep.mcpcompanion.extractmethod.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PsiRefactoringRunner
import com.github.sinlordep.mcpcompanion.common.source.psi.PsiCodeSites
import com.github.sinlordep.mcpcompanion.extractmethod.ExtractMethod
import com.intellij.openapi.project.Project

/** Wires [ExtractMethod] to the platform for [project]. Cheap; called once per tool call. */
fun extractMethodFor(project: Project): ExtractMethod {
    val files = ProjectFiles.of(project)
    return ExtractMethod(PsiMethodExtractionGateway(PsiRefactoringRunner(project, files, PsiCodeSites(project, files))))
}
