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
        is SearchScopeSpec.Module -> moduleScope(spec.name)
        is SearchScopeSpec.Directory -> directoryScope(spec.path)
        is SearchScopeSpec.File -> fileScope(spec.path)
    }

    private fun moduleScope(name: String): ToolResult<GlobalSearchScope> =
        ModuleManager.getInstance(project).findModuleByName(name)
            ?.let { ToolResult.success(GlobalSearchScope.moduleScope(it)) }
            ?: ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "No module named '$name'.",
                "Use a module name as the IDE shows it (the official get_project_modules tool lists them).",
            )

    private fun directoryScope(path: String): ToolResult<GlobalSearchScope> = files.find(path)
        ?.takeIf { it.isDirectory }
        ?.let { ToolResult.success(GlobalSearchScopesCore.directoryScope(project, it, true)) }
        ?: ToolResult.failure(
            ErrorCode.NOT_FOUND,
            "No directory at '$path'.",
            "Pass a directory path relative to the project root, or an absolute one.",
        )

    private fun fileScope(path: String): ToolResult<GlobalSearchScope> = files.find(path)
        ?.takeIf { !it.isDirectory }
        ?.let { ToolResult.success(GlobalSearchScope.fileScope(project, it)) }
        ?: ToolResult.failure(
            ErrorCode.NOT_FOUND,
            "No file at '$path'.",
            "Pass a file path relative to the project root, or an absolute one; use dir:<path> for a directory.",
        )
}
