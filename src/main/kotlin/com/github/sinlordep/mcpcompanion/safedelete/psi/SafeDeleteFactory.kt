package com.github.sinlordep.mcpcompanion.safedelete.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.source.psi.PsiCodeSites
import com.github.sinlordep.mcpcompanion.common.symbols.psi.PsiSymbolMapper
import com.github.sinlordep.mcpcompanion.common.symbols.psi.symbolLocatorsFor
import com.github.sinlordep.mcpcompanion.safedelete.SafeDelete
import com.intellij.openapi.project.Project

/** Wires [SafeDelete] to the platform for [project]. Cheap; called once per tool call. */
fun safeDeleteFor(project: Project): SafeDelete {
    val files = ProjectFiles.of(project)
    return SafeDelete(
        locators = symbolLocatorsFor(project, files),
        gateway = PsiSafeDeleteGateway(
            project,
            SafeDeleteUsageClassifier(project, PsiCodeSites(project, files), PsiSymbolMapper(project, files)),
        ),
    )
}
