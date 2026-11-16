package com.github.sinlordep.mcpcompanion.contract

import com.intellij.refactoring.safeDelete.SafeDeleteProcessorDelegate
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Pins the safe-delete delegates that `PsiSafeDeleteGateway` was checked against (docs/notes-step2.md, "Safe delete
 * without UI"). If one fails after an IDE update, redo that check before changing the expectation: a new or changed
 * delegate may consult the user in `findUsages`, `findConflicts` or `prepareForDeletion`.
 */
class SafeDeleteDelegatesContractTest : BasePlatformTestCase() {

    private fun delegates() = SafeDeleteProcessorDelegate.EP_NAME.extensionList.map { it.javaClass.name }

    fun testTheJavaDelegateWhoseConflictsWeAskForStillExists() {
        assertContainsElements(delegates(), "com.intellij.refactoring.safeDelete.JavaSafeDeleteProcessor")
    }

    fun testTheDelegatesAreTheOnesThatWereChecked() {
        assertEquals(
            setOf(
                "com.intellij.refactoring.safeDelete.JavaSafeDeleteProcessor",
                "org.jetbrains.kotlin.idea.k2.refactoring.safeDelete.KotlinFirSafeDeleteProcessor",
            ),
            delegates().filter { it.startsWith("com.intellij.refactoring.safeDelete.Java") || "kotlin" in it }.toSet(),
        )
    }
}
