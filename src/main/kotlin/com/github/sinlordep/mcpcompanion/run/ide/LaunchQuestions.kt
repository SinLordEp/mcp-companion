package com.github.sinlordep.mcpcompanion.run.ide

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolError
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.ide.macro.MacroManager
import com.intellij.ide.macro.PromptingMacro
import com.intellij.openapi.util.WriteExternalException
import org.jdom.Element

/**
 * Settings of a run configuration with which the IDE would ask the user during a launch; `mc_run` refuses those
 * launches instead (see `docs/notes-step3.md`):
 *
 * - "Show this page" (`isEditBeforeRun`): the IDE opens the configuration editor before every run;
 * - prompting macros (`$Prompt$`, `$FilePrompt$`, any `PromptingMacro`) anywhere in the configuration (arguments, VM
 *   options, working directory, environment): they open an input dialog while the command line is built;
 * - before-launch tasks other than the checked build tasks ([CHECKED_BEFORE_RUN_TASKS]): a provider of another plugin
 *   may open UI (e.g. running another configuration asks to stop it).
 *
 * Call inside a read action.
 */
internal object LaunchQuestions {

    /** Provider ids of the Java plugin's build tasks, checked to show no UI. */
    val CHECKED_BEFORE_RUN_TASKS: Set<String> = setOf("Make", "MakeNoErrorCheck", "MakeProject")

    private val MACRO = Regex("""\$([A-Za-z_][\w.]*)(?::[^$]*)?\$""")

    fun refusal(settings: RunnerAndConfigurationSettings): ToolResult.Failure? {
        val name = settings.name
        val prompts = promptingMacrosIn(settings.configuration)
        val foreignTasks = settings.configuration.beforeRunTasks.filter { it.isEnabled }
            .map { it.providerId.toString() }.filterNot { it in CHECKED_BEFORE_RUN_TASKS }
        return when {
            settings.isEditBeforeRun -> ToolResult.Failure(
                error(
                    ErrorCode.CONFLICT,
                    "'$name' asks to show its settings before each run (Show this page); the IDE would open the " +
                        "configuration editor. Nothing was started.",
                    "Turn off \"Show this page\" for it in Run | Edit Configurations, or start it from the IDE.",
                ),
            )

            prompts.isNotEmpty() -> ToolResult.Failure(
                error(
                    ErrorCode.UNSUPPORTED,
                    "'$name' uses macros that ask the user for input while the command line is built: " +
                        "${prompts.joinToString { "$$it$" }}. Nothing was started.",
                    "Replace them with fixed values, or start it from the IDE.",
                ),
            )

            foreignTasks.isNotEmpty() -> ToolResult.Failure(
                error(
                    ErrorCode.UNSUPPORTED,
                    "'$name' has before-launch tasks this tool does not run, because they may ask the user: " +
                        "${foreignTasks.joinToString()}.",
                    "Run it from the IDE, or keep only Build among its before-launch tasks.",
                ),
            )

            else -> null
        }
    }

    /** Names of prompting macros used anywhere in the configuration's stored settings, in order of appearance. */
    fun promptingMacrosIn(configuration: RunConfiguration): List<String> {
        val prompting = MacroManager.getInstance().macros.filterIsInstance<PromptingMacro>().map { it.name }.toSet()
        if (prompting.isEmpty()) return emptyList()
        val state = Element("configuration")
        try {
            configuration.writeExternal(state)
        } catch (_: WriteExternalException) {
            // A configuration that cannot store its settings cannot be checked; its launch decides.
            return emptyList()
        }
        return texts(state).flatMap { text -> MACRO.findAll(text).map { it.groupValues[1] } }
            .filter { it in prompting }.distinct().toList()
    }

    private fun texts(element: Element): Sequence<String> = sequence {
        element.attributes.forEach { yield(it.value) }
        yield(element.text)
        element.children.forEach { yieldAll(texts(it)) }
    }

    private fun error(code: ErrorCode, message: String, nextStep: String) = ToolError(code, message, nextStep)
}
