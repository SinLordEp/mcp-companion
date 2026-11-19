package com.github.sinlordep.mcpcompanion.move.psi

import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PreparedRefactoring
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

/**
 * Moving classes of one language with the IDE's own move refactoring, without its dialogs. Registered from the
 * optional descriptor of the language plugin it needs (for Java: `META-INF/mcp-companion-java.xml`). Called inside a
 * read action.
 */
interface ClassMoveSupport {

    /**
     * Sets up moving [declaration] (a writable project declaration) to [targetPackage] in [sourceRoot] (a source root
     * of the project; null: the declaration's current one), finds what it would change and its conflicts. Null when
     * [declaration] is not of this support's language; a failure when it cannot be moved this way.
     */
    fun prepare(
        declaration: PsiElement,
        targetPackage: String,
        sourceRoot: VirtualFile?,
    ): ToolResult<PreparedRefactoring>?

    companion object {
        val EP: ExtensionPointName<ClassMoveSupport> = ExtensionPointName("com.github.sinlordep.mcpcompanion.classMove")
    }
}
