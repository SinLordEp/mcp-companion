package com.github.sinlordep.mcpcompanion.run.java

import com.github.sinlordep.mcpcompanion.common.build.BuildResult
import com.github.sinlordep.mcpcompanion.common.build.BuildRun
import com.github.sinlordep.mcpcompanion.common.build.BuildScope
import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.common.build.java.ProjectTaskBuild
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.run.CompilerError
import com.github.sinlordep.mcpcompanion.run.HotSwapReport
import com.github.sinlordep.mcpcompanion.run.ide.HotSwapSession
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.impl.HotSwapManager
import com.intellij.debugger.impl.HotSwapProgress
import com.intellij.debugger.settings.DebuggerSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.ui.MessageCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Build, then reload the changed classes into one JVM debug session: the steps of the IDE's "Reload Changed
 * Classes" (a `ProjectTaskManager` build, see [ProjectTaskBuild]), through the public `HotSwapManager` API with a
 * progress of our own that collects the messages instead of showing notifications. The rule where the IDE would
 * ask: while the session is paused at a breakpoint and the debugger's "warn about a possible hang" setting is on,
 * the IDE asks; this refuses instead (the setting is read, never changed).
 *
 * The hot-swap manager counts a class as reloaded once it tried, even when the JVM refused it. So that a later call
 * does not report "nothing to reload" while the JVM still runs the old code, refused classes are remembered on the
 * debug process until a reload of them succeeds.
 */
internal class JvmHotSwapSession(
    private val project: Project,
    private val session: DebuggerSession,
    private val build: ProjectTaskBuild,
) : HotSwapSession {

    override val name: String get() = session.sessionName

    override suspend fun reload(): ToolResult<HotSwapReport> {
        if (!session.isAttached) {
            return ToolResult.success(HotSwapReport.NotPossible(name, "The debugger is not attached to the JVM."))
        }
        if (session.isPaused && DebuggerSettings.getInstance().HOTSWAP_HANG_WARNING_ENABLED) {
            return ToolResult.failure(
                ErrorCode.CONFLICT,
                "'$name' is paused at a breakpoint; reloading classes now may hang the JVM, and the IDE would ask " +
                    "first (Settings | Build, Execution, Deployment | Debugger | HotSwap). Nothing was compiled.",
                "Resume the session (official xdebug_control_session RESUME), then retry.",
            )
        }
        val run = build.run(BuildScope.AllModules)
        return when (run.result) {
            BuildResult.ABORTED -> ToolResult.failure(
                ErrorCode.CONFLICT,
                "The build was aborted; nothing was reloaded.",
                "Retry when no other build runs.",
            )

            BuildResult.FAILED -> ToolResult.success(HotSwapReport.CompilationFailed(name, errorsOf(run)))

            BuildResult.SUCCEEDED -> ToolResult.success(withContext(Dispatchers.IO) { scanAndReload() })
        }
    }

    private fun errorsOf(run: BuildRun): List<CompilerError> = run.problems
        .filter { it.severity == ProblemSeverity.ERROR }
        .take(MAX_ERRORS)
        .map { CompilerError(it.message, it.path, it.line, it.column) }
        .ifEmpty { listOf(UNREADABLE) }

    /** Blocking: the hot-swap manager waits for the debugger thread. Runs off the EDT and outside read actions. */
    private suspend fun scanAndReload(): HotSwapReport {
        val context = currentCoroutineContext()
        val progress = CollectingProgress(project) { !context.isActive }
        val refused = session.process.getUserData(REFUSED).orEmpty()
        val changed = HotSwapManager.scanForModifiedClasses(listOf(session), progress)
        val classes = changed[session]?.keys?.sorted().orEmpty()
        if (classes.isEmpty()) {
            return if (refused.isEmpty()) {
                HotSwapReport.NothingToReload(name, progress.notes)
            } else {
                HotSwapReport.Rejected(name, refused.keys.sorted(), refused.values.distinct())
            }
        }
        HotSwapManager.reloadModifiedClasses(changed, progress)
        val stillRefused = if (progress.errors.isEmpty()) {
            refused - classes.toSet()
        } else {
            refused + classes.associateWith { progress.errors.joinToString("; ") }
        }
        session.process.putUserData(REFUSED, stillRefused.ifEmpty { null })
        return when {
            progress.errors.isNotEmpty() -> HotSwapReport.Rejected(name, classes, progress.errors)
            stillRefused.isEmpty() -> HotSwapReport.Reloaded(name, classes, progress.notes)
            else -> HotSwapReport.Reloaded(name, classes, progress.notes + stillRefusedNote(stillRefused))
        }
    }

    private fun stillRefusedNote(refused: Map<String, String>): String =
        "Still running the old code of ${refused.keys.sorted().joinToString()}: the JVM refused it earlier " +
            "(${refused.values.distinct().joinToString("; ")})."

    /** Collects the hot-swap messages by category; cancelled with the MCP call. */
    private class CollectingProgress(project: Project, private val callCancelled: () -> Boolean) :
        HotSwapProgress(project) {

        val errors: MutableList<String> = mutableListOf()
        val notes: MutableList<String> = mutableListOf()

        override fun addMessage(session: DebuggerSession?, type: Int, text: String) {
            synchronized(this) { if (type == MessageCategory.ERROR) errors += text else notes += text }
        }

        override fun isCancelled(): Boolean = super.isCancelled() || callCancelled()

        override fun setText(text: String) = Unit

        override fun setTitle(title: String) = Unit

        override fun setFraction(v: Double) = Unit

        override fun setDebuggerSession(debuggerSession: DebuggerSession?) = Unit
    }

    private companion object {
        const val MAX_ERRORS = 50

        /** A failed build whose messages this plugin could not read (none in the formats it knows). */
        val UNREADABLE = CompilerError(
            "The build failed without a compiler message this tool can read. The official build_project tool " +
                "lists the messages of the Build tool window.",
            path = null,
            line = null,
            column = null,
        )

        /** Classes the JVM refused, with the reason, kept on the debug process until a reload of them succeeds. */
        val REFUSED: Key<Map<String, String>> = Key.create("mcpCompanion.hotSwap.refusedClasses")
    }
}
