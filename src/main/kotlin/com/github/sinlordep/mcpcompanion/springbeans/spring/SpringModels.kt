package com.github.sinlordep.mcpcompanion.springbeans.spring

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.spring.CommonSpringModel
import com.intellij.spring.SpringManager
import com.intellij.spring.model.SpringBeanPointer

/** A bean with the model it was found in, which decides its injections. */
internal data class ModelBean(val pointer: SpringBeanPointer<*>, val model: CommonSpringModel)

/**
 * The IDE's Spring models: per module the combined model of its application contexts (from a Spring facet or detected
 * by Spring Boot) with the models of the modules it depends on. Call inside a read action.
 */
internal class SpringModels(private val project: Project, private val descriptions: BeanDescriptions) {

    /** Module [name], or every module for null; `NOT_FOUND` for an unknown name. Needs no index. */
    fun modules(name: String?): ToolResult<List<Module>> {
        val manager = ModuleManager.getInstance(project)
        if (name == null) return ToolResult.success(manager.modules.sortedBy { it.name })
        return manager.findModuleByName(name)?.let { ToolResult.success(listOf(it)) } ?: ToolResult.failure(
            ErrorCode.NOT_FOUND,
            "No module named '$name'.",
            "mc_modules action=list shows the module names; omit module to read the whole project.",
        )
    }

    /** The combined models of [modules], for the modules that have any Spring model. */
    fun of(modules: List<Module>): List<CommonSpringModel> {
        val spring = SpringManager.getInstance(project)
        return modules.filter { !it.isDisposed && spring.getAllModels(it).isNotEmpty() }
            .map { spring.getCombinedModel(it) }
    }

    /** Every bean of [models], each once (with the first model that has it). */
    fun beansOf(models: List<CommonSpringModel>): List<ModelBean> {
        val seen = LinkedHashMap<String, ModelBean>()
        for (model in models) {
            for (pointer in model.allCommonBeans) {
                ProgressManager.checkCanceled()
                if (pointer.isValid) seen.putIfAbsent(descriptions.idOf(pointer), ModelBean(pointer, model))
            }
        }
        return seen.values.toList()
    }

    /** The class of [qualifiedName] in the project or its libraries. */
    fun findClass(qualifiedName: String): PsiClass? =
        JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.allScope(project))
}
