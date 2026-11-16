package com.github.sinlordep.mcpcompanion.navigation.implementations.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.scope.psi.PsiSearchScopes
import com.github.sinlordep.mcpcompanion.common.symbols.psi.PsiSymbolMapper
import com.github.sinlordep.mcpcompanion.common.symbols.psi.symbolLocatorsFor
import com.github.sinlordep.mcpcompanion.navigation.implementations.FindImplementations
import com.intellij.openapi.project.Project

/** Wires [FindImplementations] to the platform for [project]. Cheap; called once per tool call. */
fun findImplementationsFor(project: Project): FindImplementations {
    val files = ProjectFiles.of(project)
    return FindImplementations(
        locators = symbolLocatorsFor(project, files),
        search = PsiImplementationSearchGateway(
            project,
            PsiSearchScopes(project, files),
            PsiSymbolMapper(project, files),
        ),
    )
}
