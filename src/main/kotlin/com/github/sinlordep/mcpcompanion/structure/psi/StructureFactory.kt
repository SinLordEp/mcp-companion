package com.github.sinlordep.mcpcompanion.structure.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.structure.OutlineFile
import com.intellij.openapi.project.Project

/** Wires [OutlineFile] to the platform for [project]. Cheap; called once per tool call. */
fun outlineFileFor(project: Project): OutlineFile =
    OutlineFile(PsiStructureGateway(project, ProjectFiles.of(project)) { DeclarationFactsProvider.EP.extensionList })
