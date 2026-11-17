package com.github.sinlordep.mcpcompanion.modules

import com.github.sinlordep.mcpcompanion.common.paging.page
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap

/** `list`: modules by name with build system, SDK, language level, content roots and their folders. */
class ListModules(private val model: ProjectModelGateway) : ModulesActionHandler {

    override val action = ModulesAction.LIST
    override val parameters = setOf(ModulesParameter.MODULE, ModulesParameter.LIMIT, ModulesParameter.OFFSET)

    override suspend fun handle(request: ModulesArguments): ToolResult<ModulesOutcome> =
        request.pageRequest().flatMap { pageRequest ->
            val all = model.modules().sortedBy { it.name }
            val selected = request.module?.let { name -> all.filter { it.name == name } } ?: all
            if (request.module != null && selected.isEmpty()) {
                unknownModule(request.module)
            } else {
                ToolResult.success(ModulesOutcome.Modules(selected.asSequence().page(pageRequest), selected.size))
            }
        }
}

internal fun unknownModule(name: String): ToolResult<Nothing> = ToolResult.failure(
    ErrorCode.NOT_FOUND,
    "No module is named '$name'.",
    "Module names are exact and case-sensitive; action=list shows them.",
)
