package com.github.sinlordep.mcpcompanion.run.ide

import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointListener
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import java.util.IdentityHashMap

/**
 * Connects every registered [TestResultsSupport] to the [ProcessTracker]'s records for as long as [parent] lives, also
 * supports loaded later (heard while [scope] is active). A test event goes to the record of its process handler
 * ([testsOf]); events of processes the tracker does not know (or released) are dropped. When a support is unloaded,
 * the trees it created are dropped from every record ([all]): they must not outlive its classes.
 */
internal class TestResultsObservation(
    private val project: Project,
    private val parent: Disposable,
    private val scope: CoroutineScope,
    private val testsOf: (ProcessHandler) -> ProcessTests?,
    private val all: () -> List<ProcessTests>,
) : TestEventSink {
    private val lifetimes = IdentityHashMap<TestResultsSupport, Disposable>()

    override fun testEvent(handler: ProcessHandler, phase: TestPhase, tree: () -> IdeTestTree) {
        testsOf(handler)?.event(phase, tree)
    }

    fun start() {
        TestResultsSupport.EP.addExtensionPointListener(
            scope,
            object : ExtensionPointListener<TestResultsSupport> {
                override fun extensionAdded(extension: TestResultsSupport, pluginDescriptor: PluginDescriptor) =
                    observe(extension)

                override fun extensionRemoved(extension: TestResultsSupport, pluginDescriptor: PluginDescriptor) {
                    synchronized(lifetimes) { lifetimes.remove(extension) }?.let(Disposer::dispose)
                    all().forEach(ProcessTests::forget)
                }
            },
        )
        TestResultsSupport.EP.extensionList.forEach(::observe)
    }

    private fun observe(support: TestResultsSupport) {
        val lifetime = synchronized(lifetimes) {
            if (lifetimes.containsKey(support)) return
            Disposer.newDisposable(parent, "mc_run test results").also { lifetimes[support] = it }
        }
        support.observe(project, this, lifetime)
    }
}
