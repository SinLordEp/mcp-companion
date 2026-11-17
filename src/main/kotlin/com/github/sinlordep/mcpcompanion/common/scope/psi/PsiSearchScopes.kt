package com.github.sinlordep.mcpcompanion.common.scope.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore

/** Turns a [SearchScopeSpec] into a platform search scope. Call inside a read action. */
class PsiSearchScopes(private val project: Project, private val files: ProjectFiles) {

    fun resolve(spec: SearchScopeSpec): ToolResult<GlobalSearchScope> = when (spec) {
        SearchScopeSpec.Project -> ToolResult.success(GlobalSearchScope.projectScope(project))

        SearchScopeSpec.ProjectAndLibraries -> ToolResult.success(GlobalSearchScope.allScope(project))

        is SearchScopeSpec.Module -> ModuleManager.getInstance(project).findModuleByName(spec.name)
            ?.let { ToolResult.success(GlobalSearchScope.moduleScope(it)) }
            ?: ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "No module named '${spec.name}'.",
                "Use a module name as the IDE shows it (the official get_project_modules tool lists them).",
            )

        is SearchScopeSpec.Directory -> files.find(spec.path)?.takeIf { it.isDirectory }
            ?.let { ToolResult.success(GlobalSearchScopesCore.directoryScope(project, it, true)) }
            ?: ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "No directory at '${spec.path}'.",
                "Pass a directory path relative to the project root, or an absolute one.",
            )
    }
}
