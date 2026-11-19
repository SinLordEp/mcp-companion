package com.github.sinlordep.mcpcompanion.springbeans.spring

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.source.psi.PsiCodeSites
import com.github.sinlordep.mcpcompanion.springbeans.SpringBeansControl
import com.intellij.openapi.project.Project

/** Wires [SpringBeansControl] to the IDE's Spring model for [project]. Cheap; called once per tool call. */
fun springBeansControlFor(project: Project): SpringBeansControl {
    val files = ProjectFiles.of(project)
    val descriptions = BeanDescriptions(files, PsiCodeSites(project, files))
    val models = SpringModels(project, descriptions)
    return SpringBeansControl(
        IdeSpringBeansGateway(
            project = project,
            models = models,
            selection = BeanSelection(project, files, models, descriptions),
            descriptions = descriptions,
            injections = InjectionPoints(),
        ),
    )
}
