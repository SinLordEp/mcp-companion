package com.github.sinlordep.mcpcompanion.testing

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Run configuration producers for platform tests of `mc_run start_at`, standing in for JUnit, Gradle and Application,
 * which need a JDK and a build: a `*.tick` file (or a `ticks` directory) of the project produces a script
 * configuration named after it that runs [program]; a file whose name contains `twice` gets a second candidate of the
 * same type. Line positions only decide which element the context starts from, so the whole file is the target.
 */
class ScriptProducers(scripts: ScriptConfigurations, program: String, parent: Disposable) {

    init {
        val source = scripts.programFile(program)
        val factory = scripts.type
        listOf(
            TickProducer(factory, source, suffix = ""),
            TickProducer(factory, source, suffix = " (again)", twiceOnly = true),
        )
            .forEach { RunConfigurationProducer.EP_NAME.point.registerExtension(it, parent) }
    }

    private class TickProducer(
        private val factory: ConfigurationFactory,
        private val source: java.io.File,
        private val suffix: String,
        private val twiceOnly: Boolean = false,
    ) : LazyRunConfigurationProducer<ScriptConfiguration>() {

        override fun getConfigurationFactory(): ConfigurationFactory = factory

        override fun setupConfigurationFromContext(
            configuration: ScriptConfiguration,
            context: ConfigurationContext,
            sourceElement: Ref<PsiElement>,
        ): Boolean {
            val target = targetOf(context) ?: return false
            configuration.name = nameOf(target)
            configuration.source = source
            sourceElement.set(target)
            return true
        }

        override fun isConfigurationFromContext(
            configuration: ScriptConfiguration,
            context: ConfigurationContext,
        ): Boolean = targetOf(context)?.let { configuration.name == nameOf(it) } == true

        private fun targetOf(context: ConfigurationContext): PsiElement? {
            val element = context.psiLocation ?: return null
            val target = when (element) {
                is PsiDirectory -> element.takeIf { it.name == "ticks" }
                else -> element.containingFile?.takeIf { it.name.endsWith(".tick") }
            } ?: return null
            if (twiceOnly && (target as? PsiFile)?.name?.contains("twice") != true) return null
            return target
        }

        private fun nameOf(target: PsiElement): String = when (target) {
            is PsiDirectory -> "ticks in ${target.name}"
            else -> "tick ${(target as PsiFile).name}"
        } + suffix
    }
}
