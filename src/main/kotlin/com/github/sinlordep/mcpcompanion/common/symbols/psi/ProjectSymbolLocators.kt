package com.github.sinlordep.mcpcompanion.common.symbols.psi

import com.github.sinlordep.mcpcompanion.common.paths.ProjectPathRenderer
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocators
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.SystemInfoRt

/** Assembles the [SymbolLocators] for [project]: the generic position locator plus every installed language's. */
fun symbolLocatorsFor(project: Project): SymbolLocators {
    val root = project.guessProjectDir()?.path ?: project.basePath ?: "/"
    val paths = ProjectPathRenderer(root, caseSensitive = SystemInfoRt.isFileSystemCaseSensitive)
    val mapper = PsiSymbolMapper(project, paths)
    return SymbolLocators(
        byPosition = PositionSymbolLocator(project, paths, mapper),
        byQualifiedName = QualifiedNameLocatorProvider.EP.extensionList.map { it.create(project, mapper) },
    )
}
