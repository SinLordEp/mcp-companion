package com.github.sinlordep.mcpcompanion.toolsets.springbeans

import com.github.sinlordep.mcpcompanion.springbeans.BeanDependencies
import com.github.sinlordep.mcpcompanion.springbeans.BeanKind
import com.github.sinlordep.mcpcompanion.springbeans.CandidateSource
import com.github.sinlordep.mcpcompanion.springbeans.FoundBeans
import com.github.sinlordep.mcpcompanion.springbeans.IncomingResolved
import com.github.sinlordep.mcpcompanion.springbeans.InjectionKind
import com.github.sinlordep.mcpcompanion.springbeans.InjectionStatus
import com.github.sinlordep.mcpcompanion.springbeans.ResolvedInjection
import com.github.sinlordep.mcpcompanion.springbeans.SpringAnswer
import com.github.sinlordep.mcpcompanion.springbeans.SpringBean
import com.github.sinlordep.mcpcompanion.springbeans.SpringBeansOutcome
import com.github.sinlordep.mcpcompanion.toolsets.symbols.OriginOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MCP output model of `mc_spring_beans`. [status] says whether the IDE's Spring model answered ([message] says why
 * not). `list`: [total] beans, one page of [beans], [hiddenLibraryBeans]; `dependencies`: the [bean], its
 * [injections], the beans it is [injectedInto], how many beans were searched for that ([scannedBeans], [complete]),
 * and [notes] on what is not shown.
 */
@Serializable
data class SpringBeansOutput(
    val status: ModelStatusOutput,
    val message: String? = null,
    val module: String? = null,
    val total: Int? = null,
    val beans: List<BeanOutput>? = null,
    val hiddenLibraryBeans: Int? = null,
    val hasMore: Boolean? = null,
    val nextOffset: Int? = null,
    val bean: BeanOutput? = null,
    val injections: List<InjectionOutput>? = null,
    val injectedInto: List<IncomingOutput>? = null,
    val scannedBeans: Int? = null,
    val complete: Boolean? = null,
    val notes: List<String>? = null,
) {
    companion object {
        fun from(outcome: SpringBeansOutcome): SpringBeansOutput = when (outcome) {
            is SpringBeansOutcome.Beans -> when (val answer = outcome.answer) {
                is SpringAnswer.Ready -> beans(outcome.module, answer.value)
                else -> notReady(answer, outcome.module)
            }

            is SpringBeansOutcome.Dependencies -> when (val answer = outcome.answer) {
                is SpringAnswer.Ready -> dependencies(answer.value)
                else -> notReady(answer, null)
            }
        }

        private fun beans(module: String?, found: FoundBeans) = SpringBeansOutput(
            status = ModelStatusOutput.READY,
            module = module,
            total = found.total,
            beans = found.page.items.map(BeanOutput::from),
            hiddenLibraryBeans = found.hiddenLibraryBeans.takeIf { it > 0 },
            hasMore = found.page.hasMore,
            nextOffset = found.page.nextOffset,
        )

        private fun dependencies(found: BeanDependencies) = SpringBeansOutput(
            status = ModelStatusOutput.READY,
            bean = BeanOutput.from(found.bean),
            injections = found.injections.map(InjectionOutput::from),
            injectedInto = found.injectedInto.map(IncomingOutput::from),
            scannedBeans = found.scannedBeans,
            complete = found.complete,
            notes = found.notes.ifEmpty { null },
        )

        private fun notReady(answer: SpringAnswer<*>, module: String?): SpringBeansOutput = when (answer) {
            is SpringAnswer.Indexing -> SpringBeansOutput(
                status = ModelStatusOutput.INDEXING,
                message = "The IDE is indexing; its Spring model is available when indexing ends. Call again later.",
                module = module,
            )

            is SpringAnswer.NoSpringModel -> SpringBeansOutput(
                status = ModelStatusOutput.NO_SPRING_MODEL,
                message = (answer.module?.let { "Module '$it' has no" } ?: "No module of the project has a") +
                    " Spring application context the IDE knows: that needs the Spring library on the classpath and " +
                    "a detected Spring Boot application (@SpringBootApplication) or a Spring facet with its " +
                    "configuration files or classes. This is not an empty list of beans.",
                module = answer.module,
            )

            is SpringAnswer.Ready -> error("a ready answer is not 'not ready'")
        }
    }
}

/** MCP output values of `status`. */
@Serializable
enum class ModelStatusOutput {
    @SerialName("ready")
    READY,

    @SerialName("indexing")
    INDEXING,

    @SerialName("no_spring_model")
    NO_SPRING_MODEL,
}

/**
 * One bean: [name] (absent for an anonymous bean) and [aliases], [type], [kind], [scope], [profiles] (absent: every
 * profile), [primary], [module] and the place of its definition ([path], [line], [column], [origin]).
 */
@Serializable
data class BeanOutput(
    val name: String? = null,
    val aliases: List<String>? = null,
    val type: String? = null,
    val kind: BeanKindOutput,
    val scope: String,
    val profiles: List<String>? = null,
    val primary: Boolean = false,
    val module: String? = null,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val origin: OriginOutput? = null,
) {
    companion object {
        fun from(bean: SpringBean) = BeanOutput(
            name = bean.name,
            aliases = bean.aliases.ifEmpty { null },
            type = bean.type,
            kind = BeanKindOutput.of(bean.kind),
            scope = bean.scope,
            profiles = bean.profiles.ifEmpty { null },
            primary = bean.primary,
            module = bean.module,
            path = bean.location?.path,
            line = bean.location?.line,
            column = bean.location?.column,
            origin = bean.location?.origin?.let(OriginOutput::of),
        )
    }
}

/** MCP output values of a bean's `kind`. */
@Serializable
enum class BeanKindOutput {
    @SerialName("component")
    COMPONENT,

    @SerialName("bean_method")
    BEAN_METHOD,

    @SerialName("xml")
    XML,

    @SerialName("other")
    OTHER,
    ;

    companion object {
        fun of(kind: BeanKind): BeanKindOutput = when (kind) {
            BeanKind.COMPONENT -> COMPONENT
            BeanKind.BEAN_METHOD -> BEAN_METHOD
            BeanKind.XML -> XML
            BeanKind.OTHER -> OTHER
        }
    }
}

/** Another bean as an injection names it: [name], [type] and the place of its definition. */
@Serializable
data class BeanRefOutput(
    val name: String? = null,
    val type: String? = null,
    val path: String? = null,
    val line: Int? = null,
) {
    companion object {
        fun from(bean: SpringBean) = BeanRefOutput(bean.name, bean.type, bean.location?.path, bean.location?.line)
    }
}

/**
 * One injection point of the bean: [kind], [member], the field's or parameter's [name] and [type], its place,
 * [qualifier], [required], the [status] Spring's autowiring rules give it, the [injected] beans and the [candidates]
 * that fit (when they differ from [injected]), and who found them ([source]).
 */
@Serializable
data class InjectionOutput(
    val kind: InjectionKindOutput,
    val member: String,
    val name: String,
    val type: String,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val qualifier: String? = null,
    val required: Boolean = true,
    val status: InjectionStatusOutput,
    val injected: List<BeanRefOutput>? = null,
    val candidates: List<BeanRefOutput>? = null,
    val source: CandidateSourceOutput,
) {
    companion object {
        fun from(resolved: ResolvedInjection): InjectionOutput {
            val point = resolved.point
            return InjectionOutput(
                kind = InjectionKindOutput.of(point.kind),
                member = point.member,
                name = point.name,
                type = point.type,
                path = point.location?.path,
                line = point.location?.line,
                column = point.location?.column,
                qualifier = point.qualifier,
                required = point.required,
                status = InjectionStatusOutput.of(resolved.status),
                injected = resolved.injected.map(BeanRefOutput::from).ifEmpty { null },
                candidates = resolved.candidates.takeIf { it != resolved.injected }
                    ?.map(BeanRefOutput::from)?.ifEmpty { null },
                source = CandidateSourceOutput.of(point.source),
            )
        }
    }
}

/** A bean that receives the selected bean: [bean] and the injection point, with its [status]. */
@Serializable
data class IncomingOutput(
    val bean: BeanRefOutput,
    val kind: InjectionKindOutput,
    val member: String,
    val name: String,
    val path: String? = null,
    val line: Int? = null,
    val status: InjectionStatusOutput,
) {
    companion object {
        fun from(incoming: IncomingResolved): IncomingOutput {
            val point = incoming.injection.point
            return IncomingOutput(
                bean = BeanRefOutput.from(incoming.bean),
                kind = InjectionKindOutput.of(point.kind),
                member = point.member,
                name = point.name,
                path = point.location?.path,
                line = point.location?.line,
                status = InjectionStatusOutput.of(incoming.injection.status),
            )
        }
    }
}

/** MCP output values of an injection point's `kind`. */
@Serializable
enum class InjectionKindOutput {
    @SerialName("field")
    FIELD,

    @SerialName("setter")
    SETTER,

    @SerialName("method")
    METHOD,

    @SerialName("constructor_parameter")
    CONSTRUCTOR_PARAMETER,

    @SerialName("bean_method_parameter")
    BEAN_METHOD_PARAMETER,
    ;

    companion object {
        fun of(kind: InjectionKind): InjectionKindOutput = when (kind) {
            InjectionKind.FIELD -> FIELD
            InjectionKind.SETTER -> SETTER
            InjectionKind.METHOD -> METHOD
            InjectionKind.CONSTRUCTOR_PARAMETER -> CONSTRUCTOR_PARAMETER
            InjectionKind.BEAN_METHOD_PARAMETER -> BEAN_METHOD_PARAMETER
        }
    }
}

/** MCP output values of an injection point's `status`. */
@Serializable
enum class InjectionStatusOutput {
    @SerialName("resolved")
    RESOLVED,

    @SerialName("ambiguous")
    AMBIGUOUS,

    @SerialName("none")
    NONE,

    @SerialName("all")
    ALL,
    ;

    companion object {
        fun of(status: InjectionStatus): InjectionStatusOutput = when (status) {
            InjectionStatus.RESOLVED -> RESOLVED
            InjectionStatus.AMBIGUOUS -> AMBIGUOUS
            InjectionStatus.NONE -> NONE
            InjectionStatus.ALL -> ALL
        }
    }
}

/** MCP output values of an injection point's `source`. */
@Serializable
enum class CandidateSourceOutput {
    @SerialName("ide")
    IDE,

    @SerialName("type")
    TYPE,
    ;

    companion object {
        fun of(source: CandidateSource): CandidateSourceOutput = when (source) {
            CandidateSource.IDE -> IDE
            CandidateSource.TYPE -> TYPE
        }
    }
}
