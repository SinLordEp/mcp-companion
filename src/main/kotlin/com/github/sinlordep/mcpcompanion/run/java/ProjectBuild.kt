package com.github.sinlordep.mcpcompanion.run.java

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.run.CompilerError
import com.intellij.openapi.application.EDT
import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.compiler.CompilerTopics
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The build the IDE's "Reload Changed Classes" runs: an incremental `ProjectTaskManager` build of all modules, so a
 * project that delegates build and run to Gradle or Maven is built by that tool into the output directories its JVM
 * loads from. The IDE's own reload after this build is vetoed ([IdeReloadVeto]); the caller reloads itself.
 *
 * Errors: a JPS build reports them through the public `CompilerTopics.COMPILATION_STATUS` topic with file positions.
 * Delegated builds (Gradle, Maven) report them only to the Build tool window, whose listener API is experimental; then
 * the result only says that the build failed.
 */
internal class ProjectBuild(private val project: Project) {

    sealed interface Outcome {
        data object Succeeded : Outcome

        data object Aborted : Outcome

        /** [errors] is empty when the build system gave no readable messages. */
        data class Failed(val errors: List<CompilerError>) : Outcome
    }

    suspend fun run(): Outcome {
        val lifetime = Disposer.newDisposable("mc_run hotswap build")
        try {
            val context = ProjectTaskContext(Any())
            IdeReloadVeto.install(project, lifetime) { it === context }
            val messages = Collections.synchronizedList(mutableListOf<CompilerError>())
            project.messageBus.connect(lifetime).subscribe(CompilerTopics.COMPILATION_STATUS, ErrorCollector(messages))
            val result = withContext(Dispatchers.EDT) {
                val tasks = ProjectTaskManager.getInstance(project)
                suspendCancellableCoroutine { continuation ->
                    tasks.run(context, tasks.createAllModulesBuildTask(true, project))
                        .onSuccess { continuation.resume(it) }
                        .onError { continuation.resumeWithException(it) }
                }
            }
            return when {
                result.isAborted -> Outcome.Aborted
                result.hasErrors() -> Outcome.Failed(messages.take(MAX_ERRORS))
                else -> Outcome.Succeeded
            }
        } finally {
            Disposer.dispose(lifetime)
        }
    }

    /** Error messages of JPS compilations that finish while our build runs. */
    private inner class ErrorCollector(private val errors: MutableList<CompilerError>) : CompilationStatusListener {
        override fun compilationFinished(aborted: Boolean, errorCount: Int, warnings: Int, context: CompileContext) {
            if (errorCount == 0) return
            val files = ProjectFiles.of(project)
            errors += context.getMessages(CompilerMessageCategory.ERROR).map { message ->
                val position = message.navigatable as? OpenFileDescriptor
                CompilerError(
                    message = message.message,
                    path = message.virtualFile?.let(files::pathOf),
                    line = position?.line?.takeIf { it >= 0 }?.plus(1),
                    column = position?.column?.takeIf { it >= 0 }?.plus(1),
                )
            }
        }
    }

    private companion object {
        const val MAX_ERRORS = 50
    }
}
