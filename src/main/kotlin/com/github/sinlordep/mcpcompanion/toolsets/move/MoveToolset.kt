package com.github.sinlordep.mcpcompanion.toolsets.move

import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringRequest
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.github.sinlordep.mcpcompanion.move.ClassDestination
import com.github.sinlordep.mcpcompanion.move.psi.moveClassFor
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

/** MCP adapter for `mc_move`. */
class MoveToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_move")
    @McpDescription(
        description = "Moves a top-level Java class to another package, optionally into another source root, with " +
            "the IDE's Move refactoring: package and import statements and every reference in Java and Kotlin code " +
            "are updated. Use it instead of moving files and editing imports by hand. " + RefactoringParameters.FLOW +
            " The package is created when missing. Refused: a class of that name already in the target package " +
            "(CONFLICT), a target that is not a source root, nested classes and Kotlin declarations (UNSUPPORTED), " +
            "library classes. Comments, strings and non-code files are not updated.",
    )
    @Suppress("LongParameterList") // One parameter per MCP argument.
    suspend fun move(
        @McpDescription("Qualified name of the package to move to, e.g. com.example.model.") targetPackage: String,
        @McpDescription(RefactoringParameters.ACTION) action: RefactoringActionInput = RefactoringActionInput.PREVIEW,
        @McpDescription(ToolParameters.PATH) path: String? = null,
        @McpDescription(ToolParameters.LINE) line: Int? = null,
        @McpDescription(ToolParameters.COLUMN) column: Int? = null,
        @McpDescription(ToolParameters.QUALIFIED_NAME) qualifiedName: String? = null,
        @McpDescription(
            "Source or test source folder to move into, as a project path; default: the class's current one.",
        ) targetSourceRoot: String? = null,
        @McpDescription(RefactoringParameters.LIMIT) limit: Int? = null,
        @McpDescription(ToolParameters.OFFSET) offset: Int? = null,
    ): RefactoringOutput {
        val project = currentCoroutineContext().project
        return SymbolQuery.of(path, line, column, qualifiedName)
            .flatMap { symbol ->
                ClassDestination.of(targetPackage, targetSourceRoot).flatMap { destination ->
                    PageRequest.of(limit, offset).map { RefactoringRequest(symbol, destination, it) }
                }
            }
            .flatMap { moveClassFor(project).execute(action.action, it) }
            .map(RefactoringOutput::ofSymbol)
            .toMcpOutput()
    }

    companion object {
        const val ID: String = "move"
    }
}
