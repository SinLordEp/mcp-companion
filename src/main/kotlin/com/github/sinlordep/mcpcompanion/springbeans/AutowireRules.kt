package com.github.sinlordep.mcpcompanion.springbeans

/** How an injection point is served. */
enum class InjectionStatus {
    /** Exactly one bean is injected. */
    RESOLVED,

    /** Several beans fit and none wins: Spring would refuse to start. */
    AMBIGUOUS,

    /** No bean fits: Spring would refuse to start unless the point is optional. */
    NONE,

    /** A collection, array or map: every fitting bean is injected. */
    ALL,
}

/**
 * An [InjectionPoint] decided by Spring's autowiring rules: the beans that fit it ([candidates]: of its type and, with
 * a qualifier, of that name or qualifier) and those it receives ([injected]).
 */
data class ResolvedInjection(
    val point: InjectionPoint,
    val status: InjectionStatus,
    val candidates: List<SpringBean>,
    val injected: List<SpringBean>,
)

/**
 * Spring's choice among the candidates of an injection point (`DefaultListableBeanFactory.determineAutowireCandidate`,
 * simplified): an explicit qualifier keeps the beans of that name, alias or qualifier; a collection takes them all;
 * otherwise fallback beans give way to the others, a single primary bean wins, then a bean named like the field or
 * parameter. Generic type arguments and custom qualifier annotations are left to the IDE's candidate list.
 */
object AutowireRules {

    fun resolve(point: InjectionPoint): ResolvedInjection {
        val qualifier = point.qualifier
        val fitting = point.candidates
            .filter { qualifier == null || qualifier in it.names || qualifier in it.qualifiers }
            .sortedWith(SpringBean.ORDER)
        return when {
            fitting.isEmpty() -> ResolvedInjection(point, InjectionStatus.NONE, fitting, emptyList())
            point.all -> ResolvedInjection(point, InjectionStatus.ALL, fitting, fitting)
            fitting.size == 1 -> ResolvedInjection(point, InjectionStatus.RESOLVED, fitting, fitting)
            else -> choose(point, fitting)
        }
    }

    private fun choose(point: InjectionPoint, fitting: List<SpringBean>): ResolvedInjection {
        val regular = fitting.filterNot { it.fallback }.ifEmpty { fitting }
        val primaries = regular.filter { it.primary }
        val winner = when {
            regular.size == 1 -> regular.single()
            primaries.size == 1 -> primaries.single()
            primaries.size > 1 -> null
            else -> regular.filter { point.name in it.names }.singleOrNull()
        }
        return if (winner != null) {
            ResolvedInjection(point, InjectionStatus.RESOLVED, fitting, listOf(winner))
        } else {
            ResolvedInjection(point, InjectionStatus.AMBIGUOUS, fitting, emptyList())
        }
    }
}
