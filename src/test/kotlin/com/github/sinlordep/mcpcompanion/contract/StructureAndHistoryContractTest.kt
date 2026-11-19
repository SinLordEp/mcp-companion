package com.github.sinlordep.mcpcompanion.contract

import com.github.sinlordep.mcpcompanion.yield.reflectMcpToolNames
import com.intellij.history.LocalHistory
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * What `mc_file_structure`, `mc_local_history` and `mc_vcs_history` leave to the bundled server and rely on in the
 * platform (docs/notes-step10.md). If one of these fails after an IDE update, redo that check before changing the
 * expectation.
 */
class StructureAndHistoryContractTest : BasePlatformTestCase() {

    private fun served(name: String) = McpServerService.getInstance().getMcpTools().single {
        it.descriptor.name == name
    }

    /** The official tools this IDE's test setup declares (also those it does not serve). */
    private fun officialTools(): Set<String> = McpToolset.EP.extensionList
        .filterNot { it.javaClass.name.startsWith("com.github.sinlordep.mcpcompanion") }
        .flatMap { reflectMcpToolNames(it.javaClass) }
        .toSortedSet()

    /**
     * The official tools about symbols, file contents, VCS and history. `get_symbol_info` describes the symbol at one
     * position, `search_symbol` finds symbols by name across the project (no hierarchy, kinds or ranges of one file),
     * `generate_psi_tree` (Qodana's inspection authoring) parses a Java or Kotlin snippet given as text, not a project
     * file, into PSI nodes, not declarations;
     * `get_repositories` lists VCS roots, `git_status` the working tree status, the changelist tools group pending
     * changes. None outlines a file, reads the Local History, or shows a file's commits, annotation or diff. A new name
     * here may duplicate one of our tools: check it, and add a catalog entry if it replaces one.
     */
    fun testTheOfficialSymbolFileAndVcsToolsAreTheAnalysedOnes() {
        val related = Regex(
            "symbol|struct|outline|psi|histor|blame|annotat|log|diff|vcs|git|repositor|changelist|commit|revision|" +
                "label|revert",
        )
        assertEquals(
            sortedSetOf(
                "create_changelist",
                "delete_changelist",
                "generate_psi_tree",
                "get_changelists",
                "get_repositories",
                "get_symbol_info",
                "git_status",
                "move_changes_to_changelist",
                "search_symbol",
                "set_active_changelist",
            ),
            officialTools().filter { related.containsMatchIn(it) }.toSortedSet(),
        )
    }

    /** The structure and VCS tools only read; the Local History tool may revert files (undoably). */
    fun testHints() {
        for (tool in listOf("mc_file_structure", "mc_vcs_history")) {
            val hints = served(tool).descriptor.annotations
            assertEquals(tool, true, hints?.readOnlyHint)
            assertEquals(tool, false, hints?.destructiveHint)
            assertEquals(tool, false, hints?.openWorldHint)
        }
        val local = served("mc_local_history").descriptor.annotations
        assertEquals(false, local?.readOnlyHint)
        assertEquals(true, local?.destructiveHint)
    }

    /**
     * The public Local History API `mc_local_history` is built on: labels, label validity, the content of a file at a
     * time, the revert to a label. The list of change sets lives in `intellij.platform.lvcs.impl` (an optional product
     * module, not on the plugin's class path; its entry `LocalHistoryImpl` is internal), so it is not used.
     */
    fun testThePublicLocalHistoryApiIsThere() {
        val api = LocalHistory::class.java.methods.map { it.name }.toSet()
        val used = listOf("putSystemLabel", "isLabelValid", "getByteContent", "revertToLabel", "isEnabled")
        assertTrue(api.toString(), api.containsAll(used))
    }
}
