package com.github.sinlordep.mcpcompanion.springbeans

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocation

/** Another bean's injection point that receives, or may receive (`AMBIGUOUS`), the selected bean. */
data class IncomingResolved(val bean: SpringBean, val injection: ResolvedInjection)

/**
 * The answer of `dependencies`: the selected [bean], what it gets injected ([injections]) and which beans get it
 * ([injectedInto]), with [scannedBeans], [complete] and [notes] from the gateway.
 */
data class BeanDependencies(
    val bean: SpringBean,
    val injections: List<ResolvedInjection>,
    val injectedInto: List<IncomingResolved>,
    val scannedBeans: Int,
    val complete: Boolean,
    val notes: List<String>,
)

/**
 * `dependencies`: one bean, named by its name, its class or the place of its definition, with the beans injected into
 * it (fields, setters and other methods, constructor parameters, `@Bean` method parameters) and the beans it is
 * injected into, each injection point decided by Spring's autowiring rules ([AutowireRules]).
 */
internal class ShowBeanDependencies(private val gateway: SpringBeansGateway) : SpringBeansActionHandler {

    override val action = SpringBeansAction.DEPENDENCIES

    override val parameters = setOf(
        SpringBeansParameter.MODULE,
        SpringBeansParameter.BEAN,
        SpringBeansParameter.QUALIFIED_NAME,
        SpringBeansParameter.PATH,
        SpringBeansParameter.LINE,
    )

    override suspend fun handle(request: SpringBeansArguments): ToolResult<SpringBeansOutcome> =
        selectorOf(request).flatMap { selector ->
            gateway.dependencies(selector, request.module.given()).map { answer ->
                SpringBeansOutcome.Dependencies(
                    when (answer) {
                        is SpringAnswer.Ready -> SpringAnswer.Ready(decide(answer.value))
                        is SpringAnswer.Indexing -> answer
                        is SpringAnswer.NoSpringModel -> answer
                    },
                )
            }
        }

    private fun decide(raw: RawDependencies): BeanDependencies {
        val target = raw.bean.id
        val incoming = raw.injectedInto.mapNotNull { (bean, point) ->
            val resolved = AutowireRules.resolve(point)
            val receives = resolved.injected.any { it.id == target } ||
                (resolved.status == InjectionStatus.AMBIGUOUS && resolved.candidates.any { it.id == target })
            if (receives) IncomingResolved(bean, resolved) else null
        }
        return BeanDependencies(
            bean = raw.bean,
            injections = raw.injections.map(AutowireRules::resolve).sortedWith(compareBy(PLACE) { it.point.location }),
            injectedInto = incoming.sortedWith(
                compareBy<IncomingResolved, SpringBean>(SpringBean.ORDER) { it.bean }
                    .thenBy(PLACE) { it.injection.point.location },
            ),
            scannedBeans = raw.scannedBeans,
            complete = raw.complete,
            notes = raw.notes,
        )
    }

    private fun selectorOf(request: SpringBeansArguments): ToolResult<BeanSelector> {
        val name = request.bean.given()
        val qualifiedName = request.qualifiedName.given()
        val path = request.path.given()
        val line = request.line
        val forms = listOfNotNull(name, qualifiedName, path ?: line?.toString()).size
        return when {
            forms != 1 -> ToolResult.failure(ErrorCode.INVALID_ARGUMENT, "Name exactly one bean.", HOW)

            name != null -> ToolResult.success(BeanSelector.ByName(name))

            qualifiedName != null -> ToolResult.success(BeanSelector.ByClass(qualifiedName))

            path == null || line == null -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "path and line must be given together.",
                HOW,
            )

            line < 1 -> ToolResult.failure(ErrorCode.INVALID_ARGUMENT, "line is 1-based, was $line.", HOW)

            else -> ToolResult.success(BeanSelector.AtLine(path, line))
        }
    }

    companion object {
        private const val HOW = "Pass bean (a bean name or alias, as action=list shows it), or qualifiedName (the " +
            "bean's class, e.g. com.example.OrderService), or path and line of its definition (the class, the @Bean " +
            "method or the XML <bean> tag)."

        private val PLACE: Comparator<SymbolLocation?> = nullsLast(SymbolLocation.ORDER)
    }
}
