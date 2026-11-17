package com.github.sinlordep.mcpcompanion.run.ide

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.run.HotSwapGateway
import com.github.sinlordep.mcpcompanion.run.HotSwapReport
import com.github.sinlordep.mcpcompanion.run.TrackedProcess
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Reloading changed code into running debug sessions for one family of debuggers. The JVM implementation is
 * registered from `META-INF/mcp-companion-java.xml`, so without the Java plugin there is none and `hotswap` answers
 * `UNSUPPORTED`.
 */
interface HotSwapSupport {

    /** The debug session [handler] runs in, if this support can reload classes into it. */
    fun sessionOf(project: Project, handler: ProcessHandler): HotSwapSession?

    /**
     * Builds started for [configuration] must not reload classes into running debug sessions until [lifetime] ends:
     * the IDE would ask the user whether to reload. Called while `mc_run` starts [configuration].
     */
    fun suppressBuildReload(project: Project, configuration: RunConfiguration, lifetime: Disposable)

    companion object {
        val EP: ExtensionPointName<HotSwapSupport> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.hotSwapSupport")
    }
}

/** A debug session that can take changed classes. */
interface HotSwapSession {
    val name: String

    /** Compiles what changed and reloads it; never asks the user. */
    suspend fun reload(): ToolResult<HotSwapReport>
}

/** [HotSwapGateway] over the registered [HotSwapSupport]s. */
internal class ExtensionHotSwapGateway(
    private val project: Project,
    private val tracker: ProcessTracker,
    private val supports: () -> List<HotSwapSupport>,
) : HotSwapGateway {

    override suspend fun hotSwap(process: TrackedProcess): ToolResult<HotSwapReport> {
        val handler = tracker.handlerOf(process) ?: return ToolResult.failure(
            ErrorCode.CONFLICT,
            "${process.id} has no running process to reload classes into.",
            "Start it with action=start executor=debug.",
        )
        val all = supports()
        if (all.isEmpty()) {
            return ToolResult.failure(
                ErrorCode.UNSUPPORTED,
                "Hot swap needs the Java plugin, which this IDE does not have.",
                "Restart the process instead (action=restart).",
            )
        }
        val session = all.firstNotNullOfOrNull { it.sessionOf(project, handler) }
            ?: return ToolResult.success(
                HotSwapReport.NotPossible(
                    process.snapshot.name,
                    "${process.id} is not a JVM debug session this IDE can reload classes into.",
                ),
            )
        return session.reload()
    }
}
