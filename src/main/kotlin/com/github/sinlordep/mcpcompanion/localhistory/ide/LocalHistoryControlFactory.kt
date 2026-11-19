package com.github.sinlordep.mcpcompanion.localhistory.ide

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.localhistory.LocalHistoryControl
import com.intellij.openapi.project.Project
import java.time.ZoneId

/** Wires [LocalHistoryControl] to the platform for [project]. Cheap; called once per tool call. */
fun localHistoryControlFor(project: Project): LocalHistoryControl =
    LocalHistoryControl(IdeLocalHistoryGateway(project, ProjectFiles.of(project)), ZoneId.systemDefault())
