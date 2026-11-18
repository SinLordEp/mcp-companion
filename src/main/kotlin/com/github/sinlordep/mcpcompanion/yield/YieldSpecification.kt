package com.github.sinlordep.mcpcompanion.yield

/** What a yield rule looks at: one of our toolsets and the official MCP tools present right now. */
data class YieldCandidate(val toolsetId: String, val officialTools: Set<String>)

/**
 * One reason for a toolset to step aside (specification pattern). A rule returns the official tool names that make
 * the toolset redundant; an empty set means the rule sees no reason. Rules combine with [or] and [and].
 */
fun interface YieldSpecification {

    fun reasonsToYield(candidate: YieldCandidate): Set<String>

    /** Yields when either rule does; reports the reasons of both. */
    infix fun or(other: YieldSpecification): YieldSpecification = YieldSpecification { candidate ->
        reasonsToYield(candidate) + other.reasonsToYield(candidate)
    }

    /** Yields only when both rules do; reports the reasons of both. */
    infix fun and(other: YieldSpecification): YieldSpecification = YieldSpecification { candidate ->
        val mine = reasonsToYield(candidate)
        if (mine.isEmpty()) return@YieldSpecification emptySet()
        val theirs = other.reasonsToYield(candidate)
        if (theirs.isEmpty()) emptySet() else mine + theirs
    }
}

/**
 * Yields when an official equivalent from [catalog] is present, whether or not the user has switched that official
 * tool off (docs/PLAN.md section 3, owner decision of 2026-11-16).
 */
class OfficialEquivalentPresent(private val catalog: EquivalenceCatalog) : YieldSpecification {
    override fun reasonsToYield(candidate: YieldCandidate): Set<String> =
        catalog.equivalentsOf(candidate.toolsetId).intersect(candidate.officialTools)
}
