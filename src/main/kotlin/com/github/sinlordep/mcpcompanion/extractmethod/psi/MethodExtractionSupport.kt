package com.github.sinlordep.mcpcompanion.extractmethod.psi

import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PreparedRefactoring
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.extractmethod.MethodExtraction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Extracting code of one language into a new method with the IDE's own Extract Method refactoring, without its
 * editor, in-place template or dialogs. Registered from the optional descriptor of the language plugin it needs (for
 * Java: `META-INF/mcp-companion-java.xml`). Called inside a read action.
 */
interface MethodExtractionSupport {

    /**
     * Sets up [extraction] of the code at [range] of [file] (a writable project file) and finds what speaks against
     * it. Null when [file] is not of this support's language; a failure when the range is no code to extract.
     */
    fun prepare(file: PsiFile, range: TextRange, extraction: MethodExtraction): ToolResult<PreparedRefactoring>?

    companion object {
        val EP: ExtensionPointName<MethodExtractionSupport> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.methodExtraction")
    }
}
