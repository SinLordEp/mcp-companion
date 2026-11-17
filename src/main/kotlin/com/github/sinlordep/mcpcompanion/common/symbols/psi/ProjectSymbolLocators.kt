package com.github.sinlordep.mcpcompanion.common.symbols.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocators
import com.intellij.openapi.project.Project

/** Assembles the [SymbolLocators] for [project]: the generic position locator plus every installed language's. */
fun symbolLocatorsFor(project: Project, files: ProjectFiles = ProjectFiles.of(project)): SymbolLocators {
    val mapper = PsiSymbolMapper(project, files)
    return SymbolLocators(
        byPosition = PositionSymbolLocator(project, files, mapper),
        byQualifiedName = QualifiedNameLocatorProvider.EP.extensionList.map { it.create(project, mapper) },
    )
}
