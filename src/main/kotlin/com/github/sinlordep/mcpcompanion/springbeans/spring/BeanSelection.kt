package com.github.sinlordep.mcpcompanion.springbeans.spring

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.springbeans.BeanSelector
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

/**
 * Finds the one bean a [BeanSelector] names among the beans of the models: by name or alias, by its exact class, or as
 * the innermost definition (class, `@Bean` method, XML tag) that spans a line. Call inside a read action.
 */
internal class BeanSelection(
    private val project: Project,
    private val files: ProjectFiles,
    private val models: SpringModels,
    private val descriptions: BeanDescriptions,
) {

    fun select(selector: BeanSelector, all: List<ModelBean>): ToolResult<ModelBean> = when (selector) {
        is BeanSelector.ByName -> single(
            all.filter { it.pointer.name == selector.name || selector.name in it.pointer.aliases },
            selector,
        )

        is BeanSelector.ByClass -> {
            val psiClass = models.findClass(selector.qualifiedName)
            if (psiClass == null) {
                ToolResult.failure(
                    ErrorCode.NOT_FOUND,
                    "No class '${selector.qualifiedName}' in the project or its libraries.",
                    "Give the fully qualified name of the bean's class, or the bean's name with bean.",
                )
            } else {
                val manager = PsiManager.getInstance(project)
                single(all.filter { manager.areElementsEquivalent(it.pointer.beanClass, psiClass) }, selector)
            }
        }

        is BeanSelector.AtLine -> atLine(selector, all)
    }

    private fun atLine(selector: BeanSelector.AtLine, all: List<ModelBean>): ToolResult<ModelBean> {
        val file = files.find(selector.path)?.takeUnless { it.isDirectory } ?: return ToolResult.failure(
            ErrorCode.NOT_FOUND,
            "No file '${selector.path}'.",
            "Give the path of the file that defines the bean, as read_file takes it.",
        )
        val document = PsiManager.getInstance(project).findFile(file)
            ?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
        if (document == null || selector.line > document.lineCount) {
            return ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "'${selector.path}' has no line ${selector.line}.",
                "Give a line of the bean's definition (its class, @Bean method or <bean> tag).",
            )
        }
        val lineStart = document.getLineStartOffset(selector.line - 1)
        val lineEnd = document.getLineEndOffset(selector.line - 1)
        val spanning = all.mapNotNull { bean ->
            val range = descriptions.definitionOf(bean.pointer)
                ?.takeIf { it.containingFile?.virtualFile == file }?.textRange
            val spans = range != null && range.startOffset <= lineEnd && range.endOffset >= lineStart
            if (spans) bean to range.length else null
        }
        val innermost = spanning.minOfOrNull { it.second }
        return single(spanning.filter { it.second == innermost }.map { it.first }, selector)
    }

    private fun single(found: List<ModelBean>, selector: BeanSelector): ToolResult<ModelBean> = when (found.size) {
        0 -> ToolResult.failure(
            ErrorCode.NOT_FOUND,
            "No Spring bean ${describe(selector)}.",
            "action=list shows the beans (with includeLibraries=true also those of libraries).",
        )

        1 -> ToolResult.success(found.single())

        else -> ambiguous(found, selector)
    }

    private fun ambiguous(matching: List<ModelBean>, selector: BeanSelector): ToolResult<ModelBean> {
        val named = matching.take(MAX_NAMED).joinToString { candidate ->
            val bean = descriptions.describe(candidate.pointer)
            val place = bean.location?.let { "${it.path}:${it.line}" } ?: "no file"
            "${bean.name ?: "(anonymous)"} (${bean.type}) at $place"
        }
        return ToolResult.failure(
            ErrorCode.AMBIGUOUS,
            "${matching.size} beans ${describe(selector)}: $named" + if (matching.size > MAX_NAMED) ", ..." else ".",
            "Name one of them by path and line of its definition, or with module.",
        )
    }

    private fun describe(selector: BeanSelector): String = when (selector) {
        is BeanSelector.ByName -> "named '${selector.name}'"
        is BeanSelector.ByClass -> "of class '${selector.qualifiedName}'"
        is BeanSelector.AtLine -> "defined at ${selector.path}:${selector.line}"
    }

    private companion object {
        const val MAX_NAMED = 10
    }
}
