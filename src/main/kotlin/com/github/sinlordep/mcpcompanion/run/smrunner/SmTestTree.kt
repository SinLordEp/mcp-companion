package com.github.sinlordep.mcpcompanion.run.smrunner

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.run.ide.IdeTestTree
import com.github.sinlordep.mcpcompanion.run.tests.SourcePosition
import com.github.sinlordep.mcpcompanion.run.tests.TestCase
import com.github.sinlordep.mcpcompanion.run.tests.TestFailure
import com.github.sinlordep.mcpcompanion.run.tests.TestOutcome
import com.github.sinlordep.mcpcompanion.run.tests.TestRun
import com.github.sinlordep.mcpcompanion.run.tests.TestRunReading
import com.intellij.execution.Location
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope

/**
 * Reads the IDE's own test tree of one run (the console's [root]), which the IDE keeps while the run tab is open. Not
 * copied: a reading is taken per call. The tree's child lists are copy-on-write and its states volatile, so reading
 * off the EDT while the run goes on sees a consistent state of each test, possibly a few events behind.
 */
internal class SmTestTree(private val project: Project, private val root: SMTestProxy.SMRootTestProxy) : IdeTestTree {

    override fun read(finished: Boolean): TestRunReading {
        val proxies = ArrayList<SMTestProxy>()
        val cases = ArrayList<TestCase>()
        collect(root, emptyList()) { proxy, suite ->
            proxies += proxy
            cases += caseOf(proxy, suite)
        }
        val run = TestRun(cases, finished, root.duration, failureOf(root))
        return Reading(run, proxies)
    }

    /**
     * Visits the tests under [suite] in tree order: leaves, and suites that failed without any test (e.g. a class
     * whose setup failed). Suites without tests that did not fail hold nothing to report.
     */
    private fun collect(suite: SMTestProxy, path: List<String>, visit: (SMTestProxy, List<String>) -> Unit) {
        for (child in suite.children) {
            val children = child.children
            when {
                !child.isSuite -> visit(child, path)
                children.isNotEmpty() -> collect(child, path + child.name, visit)
                child.isDefect -> visit(child, path)
            }
        }
    }

    private fun caseOf(proxy: SMTestProxy, suite: List<String>) = TestCase(
        name = proxy.name,
        suite = suite,
        locationUrl = proxy.locationUrl,
        outcome = outcomeOf(proxy),
        durationMillis = proxy.duration,
        failure = failureOf(proxy),
    )

    private fun failureOf(proxy: SMTestProxy): TestFailure? {
        val message = proxy.errorMessage?.takeIf { it.isNotBlank() }
        val stackTrace = proxy.stacktrace?.takeIf { it.isNotBlank() }
        val comparison = proxy.diffViewerProvider
        if (message == null && stackTrace == null && comparison == null) return null
        return TestFailure(message, stackTrace, expected = comparison?.left, actual = comparison?.right)
    }

    private inner class Reading(override val run: TestRun, private val proxies: List<SMTestProxy>) : TestRunReading {

        /** Through the run's test locator (JUnit: the test method). Not while the IDE indexes: then all null. */
        override suspend fun positions(indices: List<Int>): List<SourcePosition?> = readAction {
            if (DumbService.isDumb(project)) return@readAction indices.map { null }
            val files = ProjectFiles.of(project)
            val scope = GlobalSearchScope.allScope(project)
            indices.map { index ->
                try {
                    proxies[index].getLocation(project, scope)?.let { positionOf(it, files) }
                } catch (_: IndexNotReadyException) {
                    null
                }
            }
        }

        private fun positionOf(location: Location<*>, files: ProjectFiles): SourcePosition? {
            val element = location.psiElement
            val file = element.containingFile ?: return null
            val virtualFile = location.virtualFile ?: file.virtualFile ?: return null
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
            val offset = element.textOffset.coerceIn(0, document.textLength)
            return SourcePosition(files.pathOf(virtualFile), document.getLineNumber(offset) + 1)
        }
    }

    private companion object {
        /**
         * Through the proxy's public predicates only: its `TestStateInfo.Magnitude`, which would also tell an error
         * from an assertion failure, is internal API in 263 (the Plugin Verifier reports it). The order matters: an
         * ignored or an interrupted test is a defect too. `isPassed` includes skipped tests, as the IDE counts them.
         */
        fun outcomeOf(proxy: SMTestProxy): TestOutcome = when {
            proxy.isInProgress -> TestOutcome.RUNNING
            proxy.isInterrupted -> TestOutcome.INTERRUPTED
            proxy.isIgnored -> TestOutcome.IGNORED
            proxy.isDefect -> TestOutcome.FAILED
            proxy.isPassed -> TestOutcome.PASSED
            else -> TestOutcome.NOT_RUN
        }
    }
}
