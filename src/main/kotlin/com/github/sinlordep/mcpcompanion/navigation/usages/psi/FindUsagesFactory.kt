package com.github.sinlordep.mcpcompanion.navigation.usages.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.scope.psi.PsiSearchScopes
import com.github.sinlordep.mcpcompanion.common.source.psi.PsiCodeSites
import com.github.sinlordep.mcpcompanion.common.symbols.psi.symbolLocatorsFor
import com.github.sinlordep.mcpcompanion.navigation.usages.FindUsages
import com.intellij.openapi.project.Project

/** Wires [FindUsages] to the platform for [project]. Cheap; called once per tool call. */
fun findUsagesFor(project: Project): FindUsages {
    val files = ProjectFiles.of(project)
    return FindUsages(
        locators = symbolLocatorsFor(project, files),
        usages = PsiUsageSearchGateway(project, PsiSearchScopes(project, files), PsiCodeSites(project, files)),
    )
}
