package com.github.sinlordep.mcpcompanion.sync.externalsystem

import com.github.sinlordep.mcpcompanion.sync.SyncResult
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The end of one external-system sync of the linked project at [path], whichever way it ends (from the 263 bytecode
 * of `ExternalSystemUtil.executeSync`/`handleSyncResult`):
 *
 * - success or failure of the resolve: the import callback (`onSuccess`, `onFailure` with the build tool's message);
 * - cancelled: no callback; the resolve task reports `onCancel`;
 * - the data import throws: no callback; `ProjectDataImportListener.onImportFailed` reports it;
 * - a before-sync task fails or is cancelled (`ExternalSystemTaskActivator.runTasks` returns false and `executeSync`
 *   returns without starting the resolve): the task, an `EXECUTE_TASK` of the same linked project, reports
 *   `onFailure`/`onCancel` before any resolve of ours started.
 *
 * Any other silent end is caught by the coordinator's watch over the IDE's activity.
 *
 * The callback's failure text is the stack trace of a wrapper exception for Gradle (seen end to end), so the reported
 * message comes from the resolve task's own failure event and error output instead ([FailureText]).
 */
internal class SyncEnd(private val project: Project, private val systemId: ProjectSystemId, path: String) {

    private val path = FileUtil.toSystemIndependentName(path)

    val result = CompletableDeferred<SyncResult>()

    private val failure = FailureText()

    val callback = object : ExternalProjectRefreshCallback {
        override fun onSuccess(externalTaskId: ExternalSystemTaskId, externalProject: DataNode<ProjectData>?) {
            result.complete(SyncResult.Succeeded())
        }

        override fun onFailure(externalTaskId: ExternalSystemTaskId, errorMessage: String, errorDetails: String?) {
            result.complete(failure.describe(errorMessage, errorDetails))
        }
    }

    fun listen(lifetime: Disposable) {
        val tasks = object : ExternalSystemTaskNotificationListener {
            override fun onStart(projectPath: String, id: ExternalSystemTaskId) {
                if (isOurs(projectPath, id)) failure.task = id
            }

            override fun onFailure(projectPath: String, id: ExternalSystemTaskId, exception: Exception) {
                when {
                    isOurs(projectPath, id) -> failure.exception(exception)
                    isBeforeSyncTask(projectPath, id) -> result.complete(beforeSyncFailed(exception))
                }
            }

            override fun onCancel(projectPath: String, id: ExternalSystemTaskId) {
                if (isOurs(projectPath, id) || isBeforeSyncTask(projectPath, id)) result.complete(SyncResult.Cancelled)
            }

            override fun onTaskOutput(id: ExternalSystemTaskId, text: String, outputType: ProcessOutputType) {
                if (id == failure.task && outputType.isStderr) failure.output(text)
            }
        }
        ExternalSystemProgressNotificationManager.getInstance().addNotificationListener(tasks, lifetime)
        project.messageBus.connect(lifetime).subscribe(
            ProjectDataImportListener.TOPIC,
            object : ProjectDataImportListener {
                override fun onImportFailed(projectPath: String?, t: Throwable) {
                    if (projectPath != null && FileUtil.toSystemIndependentName(projectPath) == path) {
                        result.complete(SyncResult.Failed(t.message.orEmpty().ifEmpty { t.javaClass.name }.bounded()))
                    }
                }
            },
        )
    }

    /** A task of this linked project (or one of its subprojects) that runs before our resolve started. */
    private fun isBeforeSyncTask(projectPath: String, id: ExternalSystemTaskId): Boolean {
        val normal = FileUtil.toSystemIndependentName(projectPath)
        val sameProject = normal == path || normal.startsWith("$path/")
        return failure.task == null && id.type == ExternalSystemTaskType.EXECUTE_TASK &&
            id.projectSystemId == systemId && sameProject
    }

    private fun beforeSyncFailed(exception: Exception): SyncResult.Failed {
        val reason = exception.message.orEmpty().ifBlank { exception.javaClass.name }
        return SyncResult.Failed("A before-sync task failed, so the IDE did not sync: $reason".bounded())
    }

    private fun isOurs(projectPath: String, id: ExternalSystemTaskId): Boolean =
        id.type == ExternalSystemTaskType.RESOLVE_PROJECT && id.projectSystemId == systemId &&
            FileUtil.toSystemIndependentName(projectPath) == path
}

/**
 * What a failed resolve said, gathered from its events: the messages of the failure's exception chain (causes and
 * suppressed exceptions), and the tail of the build tool's error output. Stack frames are left out.
 */
internal class FailureText {

    @Volatile
    var task: ExternalSystemTaskId? = null

    private val reasons = CopyOnWriteArrayList<String>()
    private val errorLines = ConcurrentLinkedDeque<String>()

    fun exception(exception: Throwable) {
        val seen = HashSet<Throwable>()
        val pending = ArrayDeque(listOf(exception))
        while (pending.isNotEmpty() && seen.size < MAX_CHAIN) {
            val next = pending.removeFirst()
            if (!seen.add(next)) continue
            next.message?.let(::withoutFrames)?.takeIf { it.isNotBlank() }?.let { if (it !in reasons) reasons += it }
            next.cause?.let(pending::addLast)
            next.suppressed.forEach(pending::addLast)
        }
    }

    fun output(text: String) {
        text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.forEach {
            errorLines.addLast(it)
            while (errorLines.size > MAX_OUTPUT_LINES) errorLines.pollFirst()
        }
    }

    fun describe(errorMessage: String, errorDetails: String?): SyncResult.Failed {
        val lines = errorLines.toList()
        // A wrapper's message is only its class name (Gradle: ExternalSystemPartialResolutionException); skip those.
        val useful = reasons.filterNot { WRAPPER.matches(it) }
        val wentWrong = lines.indexOfFirst { it.startsWith(WHAT_WENT_WRONG) }.takeIf { it >= 0 }
            ?.let { lines.getOrNull(it + 1)?.trim() }
        val message = useful.firstOrNull() ?: wentWrong ?: lines.firstOrNull()
            ?: withoutFrames(errorMessage).ifBlank { errorMessage }
        val details = (useful.drop(1) + lines).joinToString("\n").ifBlank { errorDetails?.let(::withoutFrames) }
        return SyncResult.Failed(message.bounded(), details?.ifBlank { null }?.bounded())
    }

    private companion object {
        const val MAX_CHAIN = 20
        const val MAX_OUTPUT_LINES = 40
        const val WHAT_WENT_WRONG = "* What went wrong:"
        val WRAPPER = Regex("^[\\w.$]+(Exception|Error):?$")
        val FRAME = Regex("^\\s*(at |\\.\\.\\. \\d+ more)")

        fun withoutFrames(text: String): String =
            text.lines().filterNot { FRAME.containsMatchIn(it) }.joinToString("\n").trim()
    }
}

private const val MAX_MESSAGE = 4_000

private fun String.bounded(): String = if (length <= MAX_MESSAGE) this else take(MAX_MESSAGE) + " [...]"
