package com.github.sinlordep.mcpcompanion.testing

import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import org.jdom.Element
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A run configuration for platform tests: runs a single-file Java program ([source]) with the test JVM's own `java`,
 * through the platform's default Run runner. No Java plugin machinery, no build.
 */
class ScriptConfigurationType :
    SimpleConfigurationType(
        ID,
        "MCP test script",
        null,
        NotNullLazyValue.createValue {
            AllIcons.Nodes.Console
        },
    ) {

    override fun createTemplateConfiguration(project: Project): RunConfiguration = ScriptConfiguration(project, this)

    companion object {
        const val ID = "mcpCompanionTestScript"
    }
}

class ScriptConfiguration(project: Project, factory: ConfigurationFactory) :
    RunConfigurationBase<Any>(project, factory, "script") {

    var source: File? = null
    var arguments: List<String> = emptyList()

    /** A runner gets no state and silently starts nothing, as `GenericProgramRunner` does for a null state. */
    var withoutState: Boolean = false

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("arguments", arguments.joinToString(" "))
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        object : SettingsEditor<ScriptConfiguration>() {
            override fun resetEditorFrom(configuration: ScriptConfiguration) = Unit

            override fun applyEditorTo(configuration: ScriptConfiguration) = Unit

            override fun createEditor(): JComponent = JPanel()
        }

    override fun checkConfiguration() {
        if (source?.isFile != true) throw RuntimeConfigurationError("No program file")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
        if (withoutState) null else commandLineState(environment)

    private fun commandLineState(environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                val java = File(System.getProperty("java.home"), "bin/java").path
                val commandLine = GeneralCommandLine(listOf(java, checkNotNull(source).path) + arguments)
                return KillableColoredProcessHandler(commandLine)
            }
        }
}

/** Registers [ScriptConfigurationType] and creates script configurations in [project]. */
class ScriptConfigurations(private val project: Project, parent: Disposable) {

    private val type = ScriptConfigurationType()

    init {
        ConfigurationType.CONFIGURATION_TYPE_EP.point.registerExtension(type, parent)
    }

    /** A configuration named [name] running [program] (Java source of a class with `main`). */
    fun add(
        name: String,
        program: String,
        vararg arguments: String,
        parallel: Boolean = false,
    ): RunnerAndConfigurationSettings {
        val file = File.createTempFile("McpScript", ".java").apply {
            deleteOnExit()
            writeText(program)
        }
        val settings = RunManager.getInstance(project).createConfiguration(name, type)
        (settings.configuration as ScriptConfiguration).apply {
            source = file
            this.arguments = arguments.toList()
            isAllowRunningInParallel = parallel
        }
        RunManager.getInstance(project).addConfiguration(settings)
        return settings
    }

    fun removeAll() {
        val manager = RunManager.getInstance(project)
        manager.allSettings.filter { it.type === type }.forEach(manager::removeConfiguration)
    }

    companion object {
        /** Prints its arguments' story: hello (stdout), oops (stderr), READY, then sleeps or exits with a code. */
        val TICKER = """
            public class Ticker {
                public static void main(String[] args) throws Exception {
                    System.out.println("hello");
                    System.err.println("oops");
                    System.out.println("READY");
                    if (args.length > 1 && args[0].equals("exit")) System.exit(Integer.parseInt(args[1]));
                    Thread.sleep(120_000);
                }
            }
        """.trimIndent()
    }
}
