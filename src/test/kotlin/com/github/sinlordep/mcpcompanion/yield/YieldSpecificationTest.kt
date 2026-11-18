package com.github.sinlordep.mcpcompanion.yield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YieldSpecificationTest {

    private val catalog = EquivalenceCatalog(mapOf("nav" to setOf("find_usages", "find_implementations")))
    private val officialPresent = OfficialEquivalentPresent(catalog)

    private fun candidate(vararg official: String) = YieldCandidate("nav", official.toSet())

    private fun always(vararg reasons: String) = YieldSpecification { reasons.toSet() }
    private val never = YieldSpecification { emptySet() }

    @Test
    fun `no equivalent present means no reason to yield`() {
        assertTrue(officialPresent.reasonsToYield(candidate("read_file", "search_text")).isEmpty())
    }

    @Test
    fun `any present equivalent is a reason, and only present ones are reported`() {
        assertEquals(setOf("find_usages"), officialPresent.reasonsToYield(candidate("find_usages", "read_file")))
    }

    @Test
    fun `toolsets without catalog entry never yield`() {
        val other = YieldCandidate("run", setOf("find_usages"))
        assertTrue(officialPresent.reasonsToYield(other).isEmpty())
    }

    @Test
    fun `or yields when either side does and merges reasons`() {
        assertEquals(setOf("a"), (always("a") or never).reasonsToYield(candidate()))
        assertEquals(setOf("b"), (never or always("b")).reasonsToYield(candidate()))
        assertEquals(setOf("a", "b"), (always("a") or always("b")).reasonsToYield(candidate()))
        assertTrue((never or never).reasonsToYield(candidate()).isEmpty())
    }

    @Test
    fun `and yields only when both sides do`() {
        assertEquals(setOf("a", "b"), (always("a") and always("b")).reasonsToYield(candidate()))
        assertTrue((always("a") and never).reasonsToYield(candidate()).isEmpty())
        assertTrue((never and always("b")).reasonsToYield(candidate()).isEmpty())
    }

    @Test
    fun `and does not evaluate the right side when the left side sees no reason`() {
        val exploding = YieldSpecification { error("must not be evaluated") }
        assertTrue((never and exploding).reasonsToYield(candidate()).isEmpty())
    }
}
