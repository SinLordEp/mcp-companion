package com.github.sinlordep.mcpcompanion.springbeans

import com.github.sinlordep.mcpcompanion.common.paging.mapNotNull
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.springbeans.SpringBeansFakes.bean
import com.github.sinlordep.mcpcompanion.springbeans.SpringBeansFakes.point
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.code
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.value
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validation, filtering, paging and the autowiring decisions of `mc_spring_beans` over a fake Spring model. */
class SpringBeansControlTest {

    private class FakeGateway : SpringBeansGateway {
        var beans: List<SpringBean> = emptyList()
        var state: SpringAnswer<Unit> = SpringAnswer.Ready(Unit)
        var raw: RawDependencies? = null
        val queries = ArrayList<BeanQuery>()
        val selectors = ArrayList<Pair<BeanSelector, String?>>()

        override suspend fun beans(
            query: BeanQuery,
            pick: (List<BeanSummary>) -> ToolResult<PickedBeans>,
        ): ToolResult<SpringAnswer<FoundBeans>> {
            queries += query
            val notReady = state
            if (notReady !is SpringAnswer.Ready) return ToolResult.success(notReady.cast())
            val byId = beans.associateBy { it.id }
            val summaries = beans.shuffled().map {
                BeanSummary(it.id, it.name, it.aliases, it.type, it.location?.origin != FileOrigin.PROJECT)
            }
            return pick(summaries).map { picked ->
                val page = picked.page.mapNotNull { byId[it.id] }
                SpringAnswer.Ready(FoundBeans(picked.total, page, picked.hiddenLibraryBeans))
            }
        }

        override suspend fun dependencies(
            selector: BeanSelector,
            module: String?,
        ): ToolResult<SpringAnswer<RawDependencies>> {
            selectors += selector to module
            val notReady = state
            if (notReady !is SpringAnswer.Ready) return ToolResult.success(notReady.cast())
            return ToolResult.success(SpringAnswer.Ready(raw!!))
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> SpringAnswer<Unit>.cast(): SpringAnswer<T> = this as SpringAnswer<T>
    }

    private val gateway = FakeGateway()
    private val control = SpringBeansControl(gateway)

    private fun list(arguments: SpringBeansArguments) =
        runBlocking { control.execute(SpringBeansAction.LIST, arguments) }

    private fun dependencies(arguments: SpringBeansArguments) =
        runBlocking { control.execute(SpringBeansAction.DEPENDENCIES, arguments) }

    private fun found(result: ToolResult<SpringBeansOutcome>): FoundBeans =
        ((result.value() as SpringBeansOutcome.Beans).answer as SpringAnswer.Ready).value

    private fun resolved(result: ToolResult<SpringBeansOutcome>): BeanDependencies =
        ((result.value() as SpringBeansOutcome.Dependencies).answer as SpringAnswer.Ready).value

    @Test
    fun `beans are sorted by name and paged, library beans hidden unless asked for`() {
        gateway.beans = listOf(
            bean("orderService"),
            bean("Clock", origin = FileOrigin.LIBRARY_CLASS),
            bean("auditLog"),
            bean("cardGateway"),
            bean(null),
        )
        val first = found(list(SpringBeansArguments(limit = 2)))
        assertEquals(4, first.total)
        assertEquals(1, first.hiddenLibraryBeans)
        assertEquals(listOf("auditLog", "cardGateway"), first.page.items.map { it.name })
        assertTrue(first.page.hasMore)
        val rest = found(list(SpringBeansArguments(limit = 2, offset = 2)))
        assertEquals(listOf("orderService", null), rest.page.items.map { it.name })
        assertFalse(rest.page.hasMore)
        val all = found(list(SpringBeansArguments(includeLibraries = true)))
        assertEquals(listOf("auditLog", "cardGateway", "Clock", "orderService", null), all.page.items.map { it.name })
        assertEquals(0, all.hiddenLibraryBeans)
    }

    @Test
    fun `name filters match names and aliases, by part or regex`() {
        gateway.beans = listOf(bean("reportFormatter", aliases = listOf("formatter")), bean("cardGateway"))
        fun names(arguments: SpringBeansArguments) = found(list(arguments)).page.items.map { it.name }
        assertEquals(listOf("reportFormatter"), names(SpringBeansArguments(name = "FORMAT")))
        assertEquals(listOf("reportFormatter"), names(SpringBeansArguments(namePattern = "^formatter$")))
        assertEquals(0, found(list(SpringBeansArguments(name = "nothing"))).total)
    }

    @Test
    fun `type and module go to the gateway, blank values count as absent`() {
        list(SpringBeansArguments(module = " shop ", type = "com.example.Gateway"))
        list(SpringBeansArguments(module = " ", type = ""))
        assertEquals(listOf(BeanQuery("shop", "com.example.Gateway"), BeanQuery(null, null)), gateway.queries)
    }

    @Test
    fun `invalid list arguments are refused`() {
        assertEquals(ErrorCode.INVALID_ARGUMENT, list(SpringBeansArguments(name = "a", namePattern = "b")).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, list(SpringBeansArguments(namePattern = "(")).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, list(SpringBeansArguments(limit = 0)).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, list(SpringBeansArguments(bean = "x")).code())
    }

    @Test
    fun `a model that is not ready is passed on as such`() {
        gateway.state = SpringAnswer.Indexing
        assertEquals(SpringAnswer.Indexing, (list(SpringBeansArguments()).value() as SpringBeansOutcome.Beans).answer)
        gateway.state = SpringAnswer.NoSpringModel("plain")
        val outcome = dependencies(SpringBeansArguments(bean = "x")).value() as SpringBeansOutcome.Dependencies
        assertEquals(SpringAnswer.NoSpringModel("plain"), outcome.answer)
    }

    @Test
    fun `a bean is named in exactly one way`() {
        gateway.raw = RawDependencies(bean("a"), emptyList(), emptyList(), 0, true, emptyList())
        dependencies(SpringBeansArguments(bean = "a", module = "m"))
        dependencies(SpringBeansArguments(qualifiedName = "shop.A"))
        dependencies(SpringBeansArguments(path = "src/A.java", line = 3))
        assertEquals(
            listOf(
                BeanSelector.ByName("a") to "m",
                BeanSelector.ByClass("shop.A") to null,
                BeanSelector.AtLine("src/A.java", 3) to null,
            ),
            gateway.selectors,
        )
        for (wrong in listOf(
            SpringBeansArguments(),
            SpringBeansArguments(bean = "a", qualifiedName = "shop.A"),
            SpringBeansArguments(bean = "a", line = 3),
            SpringBeansArguments(path = "src/A.java"),
            SpringBeansArguments(line = 3),
            SpringBeansArguments(path = "src/A.java", line = 0),
            SpringBeansArguments(bean = "a", name = "b"),
        )) {
            assertEquals(wrong.toString(), ErrorCode.INVALID_ARGUMENT, dependencies(wrong).code())
        }
    }

    @Test
    fun `injections are decided by Spring's rules and sorted by place`() {
        val card = bean("cardGateway")
        val cash = bean("cashGateway")
        val target = bean("notifier")
        gateway.raw = RawDependencies(
            bean = target,
            injections = listOf(
                point("gateway", line = 14, candidates = listOf(card, cash)),
                point("auditLog", line = 11, candidates = listOf(bean("auditLog"))),
                point("refunds", line = 17, candidates = emptyList(), required = false),
            ),
            injectedInto = emptyList(),
            scannedBeans = 3,
            complete = true,
            notes = emptyList(),
        )
        val result = resolved(dependencies(SpringBeansArguments(bean = "notifier")))
        assertEquals(
            listOf("auditLog RESOLVED [auditLog]", "gateway AMBIGUOUS []", "refunds NONE []"),
            result.injections.map { "${it.point.name} ${it.status} ${it.injected.map(SpringBean::name)}" },
        )
        assertEquals(listOf("cardGateway", "cashGateway"), result.injections[1].candidates.map { it.name })
    }

    @Test
    fun `incoming injections keep the points that receive the bean or may receive it`() {
        val clock = bean("clock")
        val other = bean("otherClock")
        val primaryOther = bean("primaryClock", primary = true)
        gateway.raw = RawDependencies(
            bean = clock,
            injections = emptyList(),
            injectedInto = listOf(
                IncomingInjection(bean("notifier"), point("clock", line = 22, candidates = listOf(clock))),
                IncomingInjection(bean("a"), point("time", line = 5, candidates = listOf(clock, other))),
                IncomingInjection(bean("b"), point("time", line = 6, candidates = listOf(clock, primaryOther))),
                IncomingInjection(bean("c"), point("all", line = 7, candidates = listOf(clock, other), all = true)),
            ),
            scannedBeans = 5,
            complete = true,
            notes = emptyList(),
        )
        val result = resolved(dependencies(SpringBeansArguments(bean = "clock")))
        assertEquals(
            listOf("a AMBIGUOUS", "c ALL", "notifier RESOLVED"),
            result.injectedInto.map { "${it.bean.name} ${it.injection.status}" },
        )
    }
}
