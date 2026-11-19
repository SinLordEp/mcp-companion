package com.github.sinlordep.mcpcompanion.springbeans

import com.github.sinlordep.mcpcompanion.common.actions.ActionRegistry
import com.github.sinlordep.mcpcompanion.common.actions.ParameterGuard
import com.github.sinlordep.mcpcompanion.common.actions.ParameterizedAction
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** One `mc_spring_beans` action; arguments outside [parameters] are rejected before [handle]. */
interface SpringBeansActionHandler :
    ParameterizedAction<SpringBeansParameter, SpringBeansArguments, SpringBeansOutcome> {
    val action: SpringBeansAction

    override val actionName: String get() = action.name.lowercase()
}

/** What an `mc_spring_beans` action answers. */
sealed interface SpringBeansOutcome {
    /** `list`: the beans of [module] (null: the project). */
    data class Beans(val module: String?, val answer: SpringAnswer<FoundBeans>) : SpringBeansOutcome

    /** `dependencies`: one bean's injections both ways. */
    data class Dependencies(val answer: SpringAnswer<BeanDependencies>) : SpringBeansOutcome
}

/** Use case of `mc_spring_beans`: dispatches each action to its handler after checking the arguments. */
class SpringBeansControl(gateway: SpringBeansGateway) {

    private val actions = ActionRegistry(
        listOf(ListBeans(gateway), ShowBeanDependencies(gateway))
            .associate { it.action to ParameterGuard(it, SpringBeansParameter::label) },
    )

    suspend fun execute(action: SpringBeansAction, arguments: SpringBeansArguments): ToolResult<SpringBeansOutcome> =
        actions.dispatch(action, arguments)
}

/** Blank strings count as not given. */
internal fun String?.given(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
