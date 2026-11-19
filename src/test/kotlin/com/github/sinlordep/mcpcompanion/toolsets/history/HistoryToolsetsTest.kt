package com.github.sinlordep.mcpcompanion.toolsets.history

import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.github.sinlordep.mcpcompanion.toolsets.localhistory.LocalHistoryActionInput
import com.github.sinlordep.mcpcompanion.toolsets.localhistory.LocalHistoryToolset
import com.github.sinlordep.mcpcompanion.toolsets.vcshistory.VcsHistoryActionInput
import com.github.sinlordep.mcpcompanion.toolsets.vcshistory.VcsHistoryToolset
import com.intellij.history.LocalHistory
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * `mc_local_history` and `mc_vcs_history` through their real gateways in the light test project. Its files live in an
 * in-memory file system, which neither the Local History nor a VCS covers, so only labels and the refusals are
 * checked here; versions, reverts, logs, blame and diffs run end to end in the sandbox IDE (docs/notes-step10.md).
 */
class HistoryToolsetsTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val localHistory get() = McpToolset.EP.extensionList.filterIsInstance<LocalHistoryToolset>().single()
    private val vcsHistory get() = McpToolset.EP.extensionList.filterIsInstance<VcsHistoryToolset>().single()

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/A.java", "class A {}\n")
        tools = ToolCalls(project)
    }

    override fun tearDown() {
        try {
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun local(
        action: LocalHistoryActionInput,
        path: String? = null,
        name: String? = null,
        label: String? = null,
        time: String? = null,
    ) = tools.call(LOCAL) { localHistory.localHistory(action, path, name, label, time) }

    private fun localError(
        action: LocalHistoryActionInput,
        path: String? = null,
        label: String? = null,
        time: String? = null,
    ) = tools.errorCode(LOCAL) { localHistory.localHistory(action, path, label = label, time = time) }

    private fun vcsError(action: VcsHistoryActionInput, path: String) =
        tools.errorCode(VCS) { vcsHistory.vcsHistory(action, path) }

    fun testLabelsArePutAndListedNewestFirst() {
        if (!LocalHistory.getInstance().isEnabled) {
            assertEquals(
                "UNSUPPORTED",
                tools.errorCode(LOCAL) {
                    localHistory.localHistory(LocalHistoryActionInput.LABEL, name = "x")
                },
            )
            return
        }
        val first = local(LocalHistoryActionInput.LABEL, name = "before step 1").label!!
        local(LocalHistoryActionInput.LABEL, name = "before step 2")
        val names = local(LocalHistoryActionInput.LABELS).labels!!.map { it.name }
        assertEquals(listOf("before step 2", "before step 1"), names.take(2))
        assertTrue(first.id.isNotBlank())
    }

    fun testLocalHistoryRefusesMissingTargetsAndUnknownLabels() {
        assertEquals("INVALID_ARGUMENT", localError(LocalHistoryActionInput.DIFF, path = "src/A.java"))
        assertEquals("NOT_FOUND", localError(LocalHistoryActionInput.DIFF, path = "src/A.java", label = "nope"))
        val missing = if (LocalHistory.getInstance().isEnabled) "NOT_FOUND" else "UNSUPPORTED"
        assertEquals(missing, localError(LocalHistoryActionInput.REVERT, path = "src/Gone.java", time = "1"))
        assertEquals("UNSUPPORTED", localError(LocalHistoryActionInput.REVERT, path = "src", time = "1"))
    }

    fun testFilesOutsideVersionControlAreUnsupportedAndMissingOnesNotFound() {
        assertEquals("UNSUPPORTED", vcsError(VcsHistoryActionInput.LOG, "src/A.java"))
        assertEquals("UNSUPPORTED", vcsError(VcsHistoryActionInput.BLAME, "src/A.java"))
        assertEquals("NOT_FOUND", vcsError(VcsHistoryActionInput.DIFF, "src/Gone.java"))
    }

    private companion object {
        const val LOCAL = "mc_local_history"
        const val VCS = "mc_vcs_history"
    }
}
