package com.github.sinlordep.mcpcompanion.changesignature.psi

import com.github.sinlordep.mcpcompanion.changesignature.SignatureChange
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PreparedRefactoring
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement

/**
 * Changing signatures of one language's functions with the IDE's own Change Signature refactoring, without its
 * dialogs. Registered from the optional descriptor of the language plugin it needs (for Java:
 * `META-INF/mcp-companion-java.xml`). Called inside a read action.
 */
interface SignatureChangeSupport {

    /**
     * Sets up [change] of [declaration] (a writable project declaration), finds what it would change and its
     * conflicts. Null when [declaration] is not a function of this support's language; a failure when the change
     * cannot be made this way (e.g. a type that does not parse).
     */
    fun prepare(declaration: PsiElement, change: SignatureChange): ToolResult<PreparedRefactoring>?

    companion object {
        val EP: ExtensionPointName<SignatureChangeSupport> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.signatureChange")
    }
}
