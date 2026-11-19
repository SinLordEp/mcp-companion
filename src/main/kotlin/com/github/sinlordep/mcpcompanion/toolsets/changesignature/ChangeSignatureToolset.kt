package com.github.sinlordep.mcpcompanion.toolsets.changesignature

import com.github.sinlordep.mcpcompanion.changesignature.SignatureChange
import com.github.sinlordep.mcpcompanion.changesignature.psi.changeSignatureFor
import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringRequest
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.refactoring.RefactoringActionInput
import com.github.sinlordep.mcpcompanion.toolsets.refactoring.RefactoringOutput
import com.github.sinlordep.mcpcompanion.toolsets.refactoring.RefactoringParameters
import com.github.sinlordep.mcpcompanion.toolsets.symbols.ToolParameters
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for `mc_change_signature`. */
class ChangeSignatureToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_change_signature")
    @McpDescription(
        description = "Changes a Java method's or constructor's parameters (add, remove, reorder, rename, retype), " +
            "return type or visibility with the IDE's Change Signature refactoring: every call site in Java and " +
            "Kotlin and every overriding method is updated. Use it instead of editing signatures and calls by hand; " +
            "to rename the method use rename_refactoring. " + RefactoringParameters.FLOW + " Rules: parameters is " +
            "the complete new list; a current parameter is kept by from (its arguments move with it), a new one " +
            "needs name, type and defaultValue (the expression inserted at existing calls); a method that " +
            "overrides or implements another is a conflict (change the topmost one); overriding methods keep " +
            "their visibility and take the new return type; callers are not adapted to a new return type (check with " +
            "mc_diagnostics). Types and values are Java source text; unknown types are INVALID_ARGUMENT. Kotlin " +
            "declarations are UNSUPPORTED.",
    )
    @Suppress("LongParameterList") // One parameter per MCP argument.
    suspend fun changeSignature(
        @McpDescription(RefactoringParameters.ACTION) action: RefactoringActionInput = RefactoringActionInput.PREVIEW,
        @McpDescription(ToolParameters.PATH) path: String? = null,
        @McpDescription(ToolParameters.LINE) line: Int? = null,
        @McpDescription(ToolParameters.COLUMN) column: Int? = null,
        @McpDescription(ToolParameters.QUALIFIED_NAME) qualifiedName: String? = null,
        @McpDescription(
            "New parameter list in order; omit to keep it. Each item: from (a current parameter's name or 0-based " +
                "position as a string), name, type, defaultValue (new parameters only).",
        ) parameters: List<ParameterInput>? = null,
        @McpDescription("New return type; omit to keep it.") returnType: String? = null,
        @McpDescription("New visibility: public, protected, package_private or private; omit to keep it.")
        visibility: VisibilityInput? = null,
        @McpDescription(RefactoringParameters.LIMIT) limit: Int? = null,
        @McpDescription(ToolParameters.OFFSET) offset: Int? = null,
    ): RefactoringOutput {
        val project = currentCoroutineContext().project
        return SymbolQuery.of(path, line, column, qualifiedName)
            .flatMap { symbol ->
                SignatureChange.of(parameters?.map(ParameterInput::toChange), returnType, visibility?.visibility)
                    .flatMap { change -> PageRequest.of(limit, offset).map { RefactoringRequest(symbol, change, it) } }
            }
            .flatMap { changeSignatureFor(project).execute(action.action, it) }
            .map(RefactoringOutput::from)
            .toMcpOutput()
    }

    companion object {
        const val ID: String = "change_signature"
    }
}
