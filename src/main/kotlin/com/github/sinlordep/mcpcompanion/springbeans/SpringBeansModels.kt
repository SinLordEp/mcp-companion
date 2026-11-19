package com.github.sinlordep.mcpcompanion.springbeans

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocation

/**
 * What the IDE's Spring model can answer right now: the answer itself, or why there is none. An empty list would
 * mislead (no beans, or no model yet?), so the reason is part of the answer.
 */
sealed interface SpringAnswer<out T> {
    data class Ready<out T>(val value: T) : SpringAnswer<T>

    /** The IDE is indexing; its Spring model is not available until it is done. */
    data object Indexing : SpringAnswer<Nothing>

    /**
     * [module] (null: the whole project) has no Spring application context the IDE knows: no Spring facet with
     * configuration files and no detected Spring Boot application, or no Spring library on the classpath.
     */
    data class NoSpringModel(val module: String?) : SpringAnswer<Nothing>
}

/** How a bean is defined. */
enum class BeanKind {
    /** A class with `@Component` or one of its stereotypes (`@Service`, `@Repository`, `@Configuration`, ...). */
    COMPONENT,

    /** A `@Bean` method. */
    BEAN_METHOD,

    /** A `<bean>` of an XML application context. */
    XML,

    /** Anything else the IDE models, e.g. a bean registered by a library or by code. */
    OTHER,
}

/**
 * Cheap facts of one bean, enough to filter, sort and page before the expensive description. [id] is opaque;
 * [library] is true for a bean defined outside the project's content (in a library, or by code without a file).
 */
data class BeanSummary(
    val id: String,
    val name: String?,
    val aliases: List<String>,
    val type: String?,
    val library: Boolean,
) {
    /** The bean's names: its name first, then its aliases. */
    val names: List<String> get() = listOfNotNull(name) + aliases
}

/**
 * One bean: [name] (null for an anonymous bean) and [aliases], its [type] (fully qualified), where it is defined
 * ([location]: the class, the `@Bean` method or the XML tag), how ([kind]), its [scope], the [profiles] it is limited
 * to (empty: always active), whether it is [primary] or a [fallback], its [qualifiers] and the [module] it is defined
 * in. [id] identifies it within one call.
 */
data class SpringBean(
    val id: String,
    val name: String?,
    val aliases: List<String>,
    val type: String?,
    val kind: BeanKind,
    val scope: String,
    val profiles: List<String>,
    val primary: Boolean,
    val fallback: Boolean,
    val qualifiers: List<String>,
    val module: String?,
    val location: SymbolLocation?,
) {
    val names: List<String> get() = listOfNotNull(name) + aliases

    companion object {
        /** By name (anonymous beans last), then by place. */
        val ORDER: Comparator<SpringBean> = compareBy<SpringBean, String?>(nullsLast()) { it.name?.lowercase() }
            .thenBy(nullsLast()) { it.name }
            .thenBy(nullsLast()) { it.location?.path }
            .thenBy { it.location?.line ?: 0 }
    }
}

/** What `list` asks the gateway for: the beans of [module] (null: all), of [type] and subtypes when not null. */
data class BeanQuery(val module: String?, val type: String?)

/** What `list` keeps of the beans: [total] that pass its filters, [hiddenLibraryBeans] left out, one [page]. */
data class PickedBeans(val total: Int, val hiddenLibraryBeans: Int, val page: Page<BeanSummary>)

/** The answer of `list`: [total] beans that pass the filters, one [page] of them, [hiddenLibraryBeans] left out. */
data class FoundBeans(val total: Int, val page: Page<SpringBean>, val hiddenLibraryBeans: Int)

/** How a client names the bean whose dependencies it wants. */
sealed interface BeanSelector {
    /** By bean name or alias. */
    data class ByName(val name: String) : BeanSelector

    /** The bean whose class is exactly this fully qualified class. */
    data class ByClass(val qualifiedName: String) : BeanSelector

    /** The bean defined at [line] of [path] (the innermost definition that spans the line). */
    data class AtLine(val path: String, val line: Int) : BeanSelector
}

/** Where a bean gets another bean. */
enum class InjectionKind { FIELD, SETTER, METHOD, CONSTRUCTOR_PARAMETER, BEAN_METHOD_PARAMETER }

/** Who chose an injection point's candidates. */
enum class CandidateSource {
    /** The IDE's own autowiring analysis (annotated fields and methods). */
    IDE,

    /** This tool: the beans of the declared type and its subtypes, found through the IDE's Spring model. */
    TYPE,
}

/**
 * One place where a bean receives others, before the autowiring rules are applied: its [kind], the [member] (field,
 * method, or the class for a constructor), the field's or parameter's [name] and declared [type], [location], an
 * explicit [qualifier], whether it is [required], whether it takes [all] matching beans (collection, array or map), the
 * beans of the right type ([candidates]) and who found them ([source]).
 */
data class InjectionPoint(
    val kind: InjectionKind,
    val member: String,
    val name: String,
    val type: String,
    val location: SymbolLocation?,
    val qualifier: String?,
    val required: Boolean,
    val all: Boolean,
    val candidates: List<SpringBean>,
    val source: CandidateSource,
)

/** One injection point of another bean ([bean]) that receives, or may receive, the selected bean. */
data class IncomingInjection(val bean: SpringBean, val point: InjectionPoint)

/**
 * The raw dependencies of [bean]: its own [injections], the injection points of other project beans whose candidates
 * include it ([injectedInto]; [scannedBeans] beans were looked at, [complete] false when the scan stopped at its
 * limit), and [notes] on what the IDE's model could not show.
 */
data class RawDependencies(
    val bean: SpringBean,
    val injections: List<InjectionPoint>,
    val injectedInto: List<IncomingInjection>,
    val scannedBeans: Int,
    val complete: Boolean,
    val notes: List<String>,
)

/** Gateway boundary: the IDE's Spring model (paid IntelliJ IDEA feature). */
interface SpringBeansGateway {

    /**
     * The beans of [query], filtered and paged by [pick] on their cheap facts (in no particular order); only the
     * picked page is described. `NOT_FOUND` for an unknown module or type.
     */
    suspend fun beans(
        query: BeanQuery,
        pick: (List<BeanSummary>) -> ToolResult<PickedBeans>,
    ): ToolResult<SpringAnswer<FoundBeans>>

    /**
     * The bean [selector] names (in the models of [module], null: all) and its injections both ways. `NOT_FOUND` when
     * no bean matches, `AMBIGUOUS` (naming the candidates) when several do.
     */
    suspend fun dependencies(selector: BeanSelector, module: String?): ToolResult<SpringAnswer<RawDependencies>>
}
