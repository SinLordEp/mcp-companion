package com.github.sinlordep.mcpcompanion.run.smrunner

import com.github.sinlordep.mcpcompanion.run.ide.TestEventSink
import com.github.sinlordep.mcpcompanion.run.ide.TestResultsSupport
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

/**
 * Test results of the platform's test runner ("SM" test consoles: JUnit and TestNG run by the IDE, Gradle test tasks
 * and others). Registered in `mcp-companion-test-runner.xml`, which loads only with the plugin
 * `com.intellij.platform.testRunner` (content module `intellij.platform.smRunner`); see `docs/notes-step3b.md`.
 */
class SmTestResultsSupport : TestResultsSupport {

    override fun observe(project: Project, sink: TestEventSink, lifetime: Disposable) {
        project.messageBus.connect(lifetime).subscribe(SMTRunnerEventsListener.TEST_STATUS, SmTestEvents(project, sink))
    }
}
