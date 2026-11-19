package com.github.sinlordep.mcpcompanion.structuralsearch.ssr

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.scope.psi.PsiSearchScopes
import com.github.sinlordep.mcpcompanion.common.source.psi.PsiCodeSites
import com.github.sinlordep.mcpcompanion.structuralsearch.StructuralSearchControl
import com.intellij.openapi.project.Project

/** Wires [StructuralSearchControl] to the IDE's Structural Search for [project]. Cheap; called once per tool call. */
fun structuralSearchControlFor(project: Project): StructuralSearchControl {
    val files = ProjectFiles.of(project)
    val templates = SearchTemplates(project)
    return StructuralSearchControl(
        IdeStructuralSearchGateway(
            project = project,
            options = SearchOptions(templates, PsiSearchScopes(project, files)),
            templates = templates,
            descriptions = MatchDescriptions(project, PsiCodeSites(project, files)),
        ),
    )
}
