package com.github.sinlordep.mcpcompanion.quickfix.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.quickfix.QuickFixControl
import com.intellij.openapi.project.Project

/** Wires [QuickFixControl] to the platform for [project]. Cheap; called once per tool call. */
fun quickFixControlFor(project: Project): QuickFixControl {
    val files = ProjectFiles.of(project)
    val checks = ModCommandChecks(files)
    return QuickFixControl(
        PsiQuickFixGateway(
            project = project,
            files = files,
            highlighting = FileHighlighting(project),
            candidates = FixCandidates { DeferredFixProvider.EP.extensionList },
            descriptions = FixDescriptions(project, checks),
            checks = checks,
        ),
    )
}
