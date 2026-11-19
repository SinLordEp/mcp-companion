package com.github.sinlordep.mcpcompanion.pullup.psi

import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PreparedRefactoring
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.pullup.MemberPullUp
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement

/**
 * Pulling members of one language's classes up into a superclass or interface with the IDE's own Pull Members Up
 * refactoring, without its dialogs. Registered from the optional descriptor of the language plugin it needs (for
 * Java: `META-INF/mcp-companion-java.xml`). Called inside a read action.
 */
interface PullUpSupport {

    /**
     * Sets up [pullUp] of [declaration] (a writable project declaration), finds what it would change and its
     * conflicts. Null when [declaration] is not of this support's language; a failure when it cannot be pulled up
     * this way (e.g. not a member, or no such superclass).
     */
    fun prepare(declaration: PsiElement, pullUp: MemberPullUp): ToolResult<PreparedRefactoring>?

    companion object {
        val EP: ExtensionPointName<PullUpSupport> = ExtensionPointName("com.github.sinlordep.mcpcompanion.pullUp")
    }
}
