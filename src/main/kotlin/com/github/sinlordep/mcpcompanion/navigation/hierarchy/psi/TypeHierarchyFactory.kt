package com.github.sinlordep.mcpcompanion.navigation.hierarchy.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.symbols.psi.PsiSymbolMapper
import com.github.sinlordep.mcpcompanion.common.symbols.psi.symbolLocatorsFor
import com.github.sinlordep.mcpcompanion.navigation.hierarchy.BuildTypeHierarchy
import com.intellij.openapi.project.Project

/** Wires [BuildTypeHierarchy] to the platform for [project]. Cheap; called once per tool call. */
fun buildTypeHierarchyFor(project: Project): BuildTypeHierarchy {
    val files = ProjectFiles.of(project)
    return BuildTypeHierarchy(
        locators = symbolLocatorsFor(project, files),
        relations = PsiTypeRelationsGateway(project, PsiSymbolMapper(project, files)),
    )
}
