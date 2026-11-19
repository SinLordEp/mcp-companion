package com.github.sinlordep.mcpcompanion.toolsets.pullup

import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringRequest
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.github.sinlordep.mcpcompanion.pullup.MemberPullUp
import com.github.sinlordep.mcpcompanion.pullup.psi.pullUpMembersFor
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

/** MCP adapter for `mc_pull_up`. */
class PullUpToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_pull_up")
    @McpDescription(
        description = "Pulls a field, method or nested class of a Java class up into a superclass or interface " +
            "with the IDE's Pull Members Up refactoring; references to moved static members are updated. Use it " +
            "instead of moving members by hand. " + RefactoringParameters.FLOW + " Rules: the member is given by " +
            "position or qualifiedName (e.g. com.example.Sub#total); withMembers names more members of the same " +
            "class to move along; makeAbstract keeps a method's body in the subclass and declares it abstract in " +
            "the target (an interface gets a default method otherwise). Conflicts, returned before anything " +
            "changes: the moved code uses members left behind or not accessible from the target, the target " +
            "already has the member, other subclasses would lack an implementation. Not done: pushing members " +
            "down, replacing code duplicating the moved methods, Kotlin declarations (UNSUPPORTED), library " +
            "classes. Check the result with mc_diagnostics.",
    )
    @Suppress("LongParameterList") // One parameter per MCP argument.
    suspend fun pullUp(
        @McpDescription(RefactoringParameters.ACTION) action: RefactoringActionInput = RefactoringActionInput.PREVIEW,
        @McpDescription(ToolParameters.PATH) path: String? = null,
        @McpDescription(ToolParameters.LINE) line: Int? = null,
        @McpDescription(ToolParameters.COLUMN) column: Int? = null,
        @McpDescription(ToolParameters.QUALIFIED_NAME) qualifiedName: String? = null,
        @McpDescription(
            "Superclass or interface to move into, qualified or simple name; default: the class's superclass.",
        ) targetClass: String? = null,
        @McpDescription("Simple names of more members of the same class to move along, e.g. a field the method uses.")
        withMembers: List<String>? = null,
        @McpDescription("Methods only: declare them abstract in the target and keep the bodies. Default false.")
        makeAbstract: Boolean? = null,
        @McpDescription(RefactoringParameters.LIMIT) limit: Int? = null,
        @McpDescription(ToolParameters.OFFSET) offset: Int? = null,
    ): RefactoringOutput {
        val project = currentCoroutineContext().project
        return SymbolQuery.of(path, line, column, qualifiedName)
            .flatMap { symbol ->
                MemberPullUp.of(targetClass, withMembers, makeAbstract).flatMap { pullUp ->
                    PageRequest.of(limit, offset).map { RefactoringRequest(symbol, pullUp, it) }
                }
            }
            .flatMap { pullUpMembersFor(project).execute(action.action, it) }
            .map(RefactoringOutput::ofSymbol)
            .toMcpOutput()
    }

    companion object {
        const val ID: String = "pull_up"
    }
}
