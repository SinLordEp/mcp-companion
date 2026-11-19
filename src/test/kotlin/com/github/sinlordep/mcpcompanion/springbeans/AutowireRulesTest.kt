package com.github.sinlordep.mcpcompanion.springbeans

import com.github.sinlordep.mcpcompanion.springbeans.SpringBeansFakes.bean
import com.github.sinlordep.mcpcompanion.springbeans.SpringBeansFakes.point
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spring's choice among the candidates of an injection point, as `mc_spring_beans` reports it. */
class AutowireRulesTest {

    private val card = bean("cardGateway", qualifiers = listOf("card"))
    private val cash = bean("cashGateway")
    private val both = listOf(card, cash)

    private fun decide(point: InjectionPoint): String =
        AutowireRules.resolve(point).let { "${it.status} ${it.injected.map(SpringBean::name)}" }

    @Test
    fun `one candidate is injected, none is none`() {
        assertEquals("RESOLVED [cashGateway]", decide(point("x", candidates = listOf(cash))))
        assertEquals("NONE []", decide(point("x", candidates = emptyList())))
        assertEquals("NONE []", decide(point("x", candidates = emptyList(), all = true)))
    }

    @Test
    fun `a qualifier keeps the bean of that name, alias or qualifier`() {
        assertEquals("RESOLVED [cardGateway]", decide(point("x", candidates = both, qualifier = "card")))
        assertEquals("RESOLVED [cashGateway]", decide(point("x", candidates = both, qualifier = "cashGateway")))
        assertEquals("NONE []", decide(point("x", candidates = both, qualifier = "nothing")))
    }

    @Test
    fun `fallback, primary and the field name decide among several`() {
        assertEquals("AMBIGUOUS []", decide(point("x", candidates = both)))
        assertEquals("RESOLVED [cashGateway]", decide(point("cashGateway", candidates = both)))
        assertEquals(
            "RESOLVED [cardGateway]",
            decide(point("x", candidates = listOf(bean("cardGateway", primary = true), cash))),
        )
        assertEquals(
            "AMBIGUOUS []",
            decide(point("x", candidates = listOf(bean("a", primary = true), bean("b", primary = true)))),
        )
        assertEquals(
            "RESOLVED [cashGateway]",
            decide(point("x", candidates = listOf(bean("cardGateway", fallback = true), cash))),
        )
    }

    @Test
    fun `a collection takes every fitting bean`() {
        assertEquals("ALL [cardGateway, cashGateway]", decide(point("x", candidates = both, all = true)))
        assertEquals("ALL [cardGateway]", decide(point("x", candidates = both, all = true, qualifier = "card")))
    }
}
