package com.github.sinlordep.mcpcompanion.run.java

import com.github.sinlordep.mcpcompanion.run.ide.HotSwapSession
import com.github.sinlordep.mcpcompanion.run.ide.HotSwapSupport
import com.intellij.debugger.DebuggerManagerEx
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

/**
 * JVM hot swap for Java, Kotlin and other JVM debug sessions. Registered in `mcp-companion-java.xml`.
 *
 * It does not use `HotSwapUI.reloadChangedClasses`, which may ask whether to reload (`RunHotswapDialog`) or warn about
 * a possible hang (a modal message); see `docs/notes-step3.md`. [JvmHotSwapSession] runs the same steps itself.
 */
class JvmHotSwapSupport : HotSwapSupport {

    override fun sessionOf(project: Project, handler: ProcessHandler): HotSwapSession? =
        DebuggerManagerEx.getInstanceEx(project).sessions
            .firstOrNull { it.process.processHandler === handler }
            ?.let { JvmHotSwapSession(project, it, ProjectBuild(project)) }

    /** Builds that start [configuration] (its before-launch Build) must not make the IDE ask to reload. */
    override fun suppressBuildReload(project: Project, configuration: RunConfiguration, lifetime: Disposable) =
        IdeReloadVeto.install(project, lifetime) { it.runConfiguration === configuration }
}
