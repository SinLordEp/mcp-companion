package com.github.sinlordep.mcpcompanion.toolsets.extractmethod

import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringRequest
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.source.SourceRange
import com.github.sinlordep.mcpcompanion.extractmethod.MethodExtraction
import com.github.sinlordep.mcpcompanion.extractmethod.psi.extractMethodFor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.refactoring.RefactoringActionInput
import com.github.sinlordep.mcpcompanion.toolsets.refactoring.RefactoringOutput
import com.github.sinlordep.mcpcompanion.toolsets.refactoring.RefactoringParameters
import com.github.sinlordep.mcpcompanion.toolsets.refactoring.VisibilityInput
import com.github.sinlordep.mcpcompanion.toolsets.symbols.ToolParameters
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for `mc_extract_method`. */
class ExtractMethodToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_extract_method")
    @McpDescription(
        description = "Extracts consecutive Java statements or one expression, given by their lines and columns, " +
            "into a new method with the IDE's Extract Method refactoring and replaces them with a call. The IDE " +
            "infers parameters, return value and exceptions; summary shows the new method's signature and the " +
            "exact code it takes. Use it instead of moving code into a method by hand. " +
            RefactoringParameters.FLOW + " Rules: the range must cover whole statements or one complete " +
            "expression (INVALID_ARGUMENT otherwise); code the IDE cannot extract (several exits, e.g. a return " +
            "next to a break) and a method of that signature already in the class are conflicts; the method " +
            "goes into the innermost named class; static: default as the IDE decides. Not done: replacing " +
            "duplicates of the code, Kotlin files (UNSUPPORTED). Check the result with mc_diagnostics.",
    )
    @Suppress("LongParameterList") // One parameter per MCP argument.
    suspend fun extractMethod(
        @McpDescription("Java file with the code: relative to the project root, or absolute.") path: String,
        @McpDescription("First line of the code, 1-based.") startLine: Int,
        @McpDescription("Last line of the code, included.") endLine: Int,
        @McpDescription("Name of the new method.") methodName: String,
        @McpDescription(RefactoringParameters.ACTION) action: RefactoringActionInput = RefactoringActionInput.PREVIEW,
        @McpDescription("Column where the code starts on startLine, 1-based; default 1.") startColumn: Int? = null,
        @McpDescription("Column after the code's last character on endLine; default: the end of the line.")
        endColumn: Int? = null,
        @McpDescription("public, protected, package_private or private (default).") visibility: VisibilityInput? = null,
        @McpDescription("true or false to require a static or an instance method; default: as the IDE decides.")
        makeStatic: Boolean? = null,
        @McpDescription(RefactoringParameters.LIMIT) limit: Int? = null,
        @McpDescription(ToolParameters.OFFSET) offset: Int? = null,
    ): RefactoringOutput {
        val project = currentCoroutineContext().project
        return SourceRange.of(path, startLine, startColumn, endLine, endColumn)
            .flatMap { range ->
                MethodExtraction.of(methodName, visibility?.visibility, makeStatic).flatMap { extraction ->
                    PageRequest.of(limit, offset).map { RefactoringRequest(range, extraction, it) }
                }
            }
            .flatMap { extractMethodFor(project).execute(action.action, it) }
            .map(RefactoringOutput::ofRange)
            .toMcpOutput()
    }

    companion object {
        const val ID: String = "extract_method"
    }
}
