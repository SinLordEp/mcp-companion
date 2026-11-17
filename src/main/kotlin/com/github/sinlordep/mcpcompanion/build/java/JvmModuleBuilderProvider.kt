package com.github.sinlordep.mcpcompanion.build.java

import com.github.sinlordep.mcpcompanion.build.ModuleBuilder
import com.github.sinlordep.mcpcompanion.build.ide.ModuleBuilderProvider
import com.github.sinlordep.mcpcompanion.common.build.BuildScope
import com.github.sinlordep.mcpcompanion.common.build.java.ProjectTaskBuild
import com.intellij.openapi.project.Project

/** `mc_build`'s builder: the shared [ProjectTaskBuild] with a module scope. Registered in `mcp-companion-java.xml`. */
class JvmModuleBuilderProvider : ModuleBuilderProvider {
    override fun builderFor(project: Project): ModuleBuilder =
        ModuleBuilder { modules -> ProjectTaskBuild(project).run(BuildScope.Modules(modules)) }
}
