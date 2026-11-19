package com.github.sinlordep.mcpcompanion.vcshistory.vcs

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.vcshistory.VcsHistoryControl
import com.intellij.openapi.project.Project

/** Wires [VcsHistoryControl] to the platform for [project]. Cheap; called once per tool call. */
fun vcsHistoryControlFor(project: Project): VcsHistoryControl =
    VcsHistoryControl(IdeVcsHistoryGateway(project, ProjectFiles.of(project)))
