package com.github.sinlordep.mcpcompanion.testing

import com.github.sinlordep.mcpcompanion.run.ide.ProcessTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Replaces the project's [ProcessTracker] with a fresh one until [disposable] ends: the light project outlives a test.
 * The tracker's scope (the platform injects a service scope) ends with [disposable] too.
 */
fun freshTracker(project: Project, disposable: Disposable): ProcessTracker {
    val scope = CoroutineScope(SupervisorJob())
    Disposer.register(disposable) { scope.cancel() }
    val tracker = ProcessTracker(project, scope)
    project.replaceService(ProcessTracker::class.java, tracker, disposable)
    return tracker
}
