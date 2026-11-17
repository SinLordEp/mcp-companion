package com.github.sinlordep.mcpcompanion.common.build.java

import com.github.sinlordep.mcpcompanion.common.build.BuildProblem
import com.github.sinlordep.mcpcompanion.common.build.BuildResult
import com.github.sinlordep.mcpcompanion.common.build.BuildRun
import com.github.sinlordep.mcpcompanion.common.build.BuildScope
import com.github.sinlordep.mcpcompanion.common.build.MessageSource
import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.intellij.execution.ExecutionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.compiler.CompilerTopics
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A build through the IDE's own build path, `ProjectTaskManager`, which honours build delegation: a project that
 * delegates building to Gradle or Maven is built by that tool, otherwise the IDE's compiler (JPS) builds it. Shared by
 * `mc_run hotswap` (all modules, like the IDE's "Reload Changed Classes") and `mc_build` (modules).
 *
 * Messages, all through public API:
 * - JPS: `CompilerTopics.COMPILATION_STATUS` hands over the `CompileContext` with errors and warnings and their
 *   positions.
 * - Delegated builds report their messages to the Build tool window, whose listener API (`BuildProgressObservable`,
 *   `AbstractViewManager.addListener`) is `@ApiStatus.Experimental`. They do run as an execution, though, so their
 *   console output is read through `ExecutionManager.EXECUTION_TOPIC` and a process listener ([BuildToolConsole]),
 *   and the compiler lines in it are parsed. Every execution that starts while the build runs is read; only lines
 *   in a compiler's message format count.
 *
 * The IDE's own reload of changed classes after this build is vetoed ([IdeReloadVeto]), so a running debug session
 * never makes the IDE ask "reload changed classes?". Never opens a dialog itself; as with any build in the IDE, a
 * failed build may open the file of the first error in an editor tab.
 *
 * Cancelling the caller stops the wait, not the IDE's build: `ProjectTaskManager` has no public cancel.
 */
class ProjectTaskBuild(private val project: Project) {

    suspend fun run(scope: BuildScope): BuildRun {
        val lifetime = Disposer.newDisposable("mcp-companion build")
        try {
            val context = ProjectTaskContext(Any())
            IdeReloadVeto.install(project, lifetime) { it === context }
            val files = ProjectFiles.of(project)
            val compiler = CompilerMessages(files)
            val console = BuildToolConsole(files.paths, lifetime)
            project.messageBus.connect(lifetime).apply {
                subscribe(CompilerTopics.COMPILATION_STATUS, compiler)
                subscribe(ExecutionManager.EXECUTION_TOPIC, console)
            }
            val task = readAction { taskFor(scope) }
            val result = withContext(Dispatchers.EDT) {
                suspendCancellableCoroutine { continuation ->
                    ProjectTaskManager.getInstance(project).run(context, task)
                        .onSuccess { continuation.resume(it) }
                        .onError { continuation.resumeWithException(it) }
                }
            }
            val (problems, source) = when {
                compiler.reported.get() -> compiler.problems() to MessageSource.COMPILER
                console.observed.get() -> console.problems() to MessageSource.BUILD_OUTPUT
                else -> emptyList<BuildProblem>() to MessageSource.NONE
            }
            return bounded(BuildResult.of(result.isAborted, result.hasErrors(), problems), problems, source)
        } finally {
            Disposer.dispose(lifetime)
        }
    }

    private fun taskFor(scope: BuildScope): ProjectTask {
        val tasks = ProjectTaskManager.getInstance(project)
        return when (scope) {
            BuildScope.AllModules -> tasks.createAllModulesBuildTask(true, project)

            is BuildScope.Modules -> {
                val manager = ModuleManager.getInstance(project)
                val modules = scope.names.mapNotNull(manager::findModuleByName).toTypedArray()
                // Incremental, with the modules they depend on, without runtime-only dependencies, with tests: what
                // the IDE's Build Module action builds.
                tasks.createModulesBuildTask(modules, true, true, false, true)
            }
        }
    }

    private fun bounded(result: BuildResult, problems: List<BuildProblem>, source: MessageSource): BuildRun {
        val ordered = problems.distinct().sortedBy { it.severity }
        return BuildRun(result, ordered.take(MAX_PROBLEMS), source, (ordered.size - MAX_PROBLEMS).coerceAtLeast(0))
    }

    /** Errors and warnings of JPS compilations that finish while the build runs. */
    private class CompilerMessages(private val files: ProjectFiles) : CompilationStatusListener {
        val reported = AtomicBoolean()
        private val collected = mutableListOf<BuildProblem>()

        @Synchronized
        fun problems(): List<BuildProblem> = collected.toList()

        override fun compilationFinished(aborted: Boolean, errors: Int, warnings: Int, context: CompileContext) {
            reported.set(true)
            val messages = CATEGORIES.flatMap { (category, severity) ->
                context.getMessages(category).map { message ->
                    val position = message.navigatable as? OpenFileDescriptor
                    BuildProblem(
                        severity = severity,
                        message = message.message,
                        path = message.virtualFile?.let(files::pathOf),
                        line = position?.line?.takeIf { it >= 0 }?.plus(1),
                        column = position?.column?.takeIf { it >= 0 }?.plus(1),
                    )
                }
            }
            synchronized(this) { collected += messages }
        }

        private companion object {
            val CATEGORIES = listOf(
                CompilerMessageCategory.ERROR to ProblemSeverity.ERROR,
                CompilerMessageCategory.WARNING to ProblemSeverity.WARNING,
            )
        }
    }

    private companion object {
        /** Bound of [BuildRun.problems]; errors come first. */
        const val MAX_PROBLEMS = 200
    }
}
