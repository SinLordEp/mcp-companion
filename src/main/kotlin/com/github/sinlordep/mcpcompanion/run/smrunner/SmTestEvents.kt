package com.github.sinlordep.mcpcompanion.run.smrunner

import com.github.sinlordep.mcpcompanion.run.ide.TestEventSink
import com.github.sinlordep.mcpcompanion.run.ide.TestPhase
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.project.Project

/**
 * Forwards the test events of a project's test consoles to the [TestEventSink], keyed by the process handler of the
 * run (`SMRootTestProxy.getHandler()`). The handler may be set after the first events (a Gradle test run starts its
 * tree before its console is attached to the process; seen end to end), so every event of a run tries to bind it, and
 * the sink binds once. Events arrive on the console's reader thread or the EDT; nothing here blocks.
 */
internal class SmTestEvents(private val project: Project, private val sink: TestEventSink) :
    SMTRunnerEventsAdapter() {

    private fun forward(proxy: SMTestProxy, phase: TestPhase = TestPhase.PROGRESS) {
        val root = proxy as? SMTestProxy.SMRootTestProxy ?: proxy.root ?: return
        val handler = root.handler ?: return
        sink.testEvent(handler, phase) { SmTestTree(project, root) }
    }

    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) = forward(testsRoot, TestPhase.STARTED)

    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) = forward(testsRoot, TestPhase.FINISHED)

    override fun onSuiteTreeStarted(suite: SMTestProxy) = forward(suite)

    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) = forward(testProxy)

    override fun onSuiteStarted(suite: SMTestProxy) = forward(suite)

    override fun onSuiteFinished(suite: SMTestProxy) = forward(suite)

    override fun onTestStarted(test: SMTestProxy) = forward(test)

    override fun onTestFinished(test: SMTestProxy) = forward(test)

    override fun onTestFailed(test: SMTestProxy) = forward(test)

    override fun onTestIgnored(test: SMTestProxy) = forward(test)
}
