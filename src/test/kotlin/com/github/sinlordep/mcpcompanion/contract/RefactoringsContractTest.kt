package com.github.sinlordep.mcpcompanion.contract

import com.github.sinlordep.mcpcompanion.yield.reflectMcpToolNames
import com.intellij.mcpserver.McpToolset
import com.intellij.refactoring.changeSignature.ChangeSignatureUsageProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * What `mc_move` and `mc_change_signature` rely on (docs/notes-step8.md). If one of these fails after an IDE update,
 * redo that check before changing the expectation.
 */
class RefactoringsContractTest : BasePlatformTestCase() {

    /**
     * The official refactoring tools: only rename. A new name here may duplicate `mc_move` or `mc_change_signature`:
     * check it, and add a catalog entry if it replaces a whole tool. `move_changes_to_changelist` is about VCS change
     * lists.
     */
    fun testTheOfficialRefactoringToolsAreTheAnalysedOnes() {
        val related = Regex("refactor|(^|_)move|signature|parameter|rename")
        val names = McpToolset.EP.extensionList
            .filterNot { it.javaClass.name.startsWith("com.github.sinlordep.mcpcompanion") }
            .flatMap { reflectMcpToolNames(it.javaClass) }
            .filter { related.containsMatchIn(it) }
            .toSortedSet()
        assertEquals(sortedSetOf("move_changes_to_changelist", "rename_refactoring"), names)
    }

    /**
     * The language processors whose `findUsages`, `findConflicts` and `processUsage` `mc_change_signature` calls
     * directly (never `setupDefaultValues`, which may ask): Java's, and Kotlin's for Kotlin call sites of Java
     * methods. A new or replaced processor must be checked for dialogs in those methods.
     */
    fun testTheChangeSignatureProcessorsAreTheCheckedOnes() {
        val processors = ChangeSignatureUsageProcessor.EP_NAME.extensionList.map { it.javaClass.name }
        assertEquals(
            setOf(
                "com.intellij.refactoring.changeSignature.JavaChangeSignatureUsageProcessor",
                "org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureUsageProcessor",
            ),
            processors.filter { it.startsWith("com.intellij.refactoring.changeSignature.Java") || "kotlin" in it }
                .toSet(),
        )
    }
}
