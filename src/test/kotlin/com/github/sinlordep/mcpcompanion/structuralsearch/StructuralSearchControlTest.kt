package com.github.sinlordep.mcpcompanion.structuralsearch

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.StableResults
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.source.CodeSite
import com.github.sinlordep.mcpcompanion.common.source.SourceKey
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocation
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.code
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.value
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validation, paging and template filtering of `mc_structural_search` over a fake Structural Search. */
class StructuralSearchControlTest {

    private class FakeGateway : StructuralSearchGateway {
        var keys: List<SourceKey> = emptyList()
        var failure: ToolResult<Nothing>? = null
        var templates: List<SearchTemplate> = emptyList()
        val queries = ArrayList<StructuralQuery>()
        var requestedLimit = 0

        override suspend fun search(
            query: StructuralQuery,
            limit: Int,
            pick: (List<SourceKey>) -> ToolResult<Page<SourceKey>>,
        ): ToolResult<FoundMatches> {
            queries += query
            requestedLimit = limit
            failure?.let { return it }
            return pick(keys.shuffled()).map { page ->
                FoundMatches("Java", keys.size, Page(page.items.map(::matchAt), page.offset, page.hasMore))
            }
        }

        override suspend fun templates(): ToolResult<List<SearchTemplate>> = ToolResult.success(templates)

        private fun matchAt(key: SourceKey) = StructuralMatch(
            site = CodeSite(SymbolLocation(key.file, key.offset, 1, FileOrigin.PROJECT), "x", null),
            endLine = key.offset,
            text = "x",
            textTruncated = false,
            variables = emptyList(),
        )
    }

    private val gateway = FakeGateway()
    private val control = StructuralSearchControl(gateway)

    private fun search(arguments: StructuralSearchArguments) =
        runBlocking { control.execute(StructuralSearchAction.SEARCH, arguments) }

    private fun templates(arguments: StructuralSearchArguments = StructuralSearchArguments()) =
        runBlocking { control.execute(StructuralSearchAction.TEMPLATES, arguments) }

    private fun matches(arguments: StructuralSearchArguments) =
        (search(arguments).value() as StructuralSearchOutcome.Matches).found

    private fun foundTemplates(arguments: StructuralSearchArguments = StructuralSearchArguments()) =
        (templates(arguments).value() as StructuralSearchOutcome.Templates).found

    private fun pattern(vararg constraints: VariableConstraint) =
        StructuralSearchArguments(pattern = "\$a\$.equals(\$b\$)", constraints = constraints.toList())

    @Test
    fun `a pattern runs in the project by default with all matches counted and stable pages`() {
        gateway.keys = (1..5).map { SourceKey("file:///b.java", it) } + (1..2).map { SourceKey("file:///a.java", it) }
        val first = matches(StructuralSearchArguments(pattern = "\$a\$.equals(\$b\$)", limit = 3))
        assertEquals(7, first.total)
        assertEquals(listOf("file:///a.java:1", "file:///a.java:2", "file:///b.java:1"), first.page.items.map(::key))
        assertTrue(first.page.hasMore)
        val second = matches(StructuralSearchArguments(pattern = "\$a\$.equals(\$b\$)", limit = 3, offset = 3))
        assertEquals(listOf(2, 3, 4), second.page.items.map { it.site.location.line })
        val query = gateway.queries.first()
        assertEquals(PatternSource.Text("\$a\$.equals(\$b\$)"), query.source)
        assertEquals(SearchScopeSpec.Project, query.scope)
        assertNull(query.fileType)
        assertNull(query.caseSensitive)
        assertEquals(StableResults.SEARCH_LIMIT, gateway.requestedLimit)
    }

    @Test
    fun `the default page holds 50 matches`() {
        gateway.keys = (1..60).map { SourceKey("file:///a.java", it) }
        assertEquals(50, matches(StructuralSearchArguments(pattern = "x")).page.items.size)
    }

    @Test
    fun `no match is an empty page`() {
        val found = matches(StructuralSearchArguments(pattern = "x"))
        assertEquals(0, found.total)
        assertTrue(found.page.items.isEmpty())
        assertFalse(found.page.hasMore)
    }

    @Test
    fun `more matches than can be paged stably is refused`() {
        gateway.keys = (0..StableResults.MAX_RESULTS).map { SourceKey("file:///a.java", it) }
        assertEquals(ErrorCode.INVALID_ARGUMENT, search(StructuralSearchArguments(pattern = "x")).code())
    }

    @Test
    fun `template, language, scope and case sensitivity are passed on`() {
        matches(
            StructuralSearchArguments(
                template = " method calls ",
                fileType = " Kotlin ",
                scope = "dir:src",
                caseSensitive = true,
            ),
        )
        val query = gateway.queries.single()
        assertEquals(PatternSource.Template("method calls"), query.source)
        assertEquals("Kotlin", query.fileType)
        assertEquals(SearchScopeSpec.Directory("src"), query.scope)
        assertEquals(true, query.caseSensitive)
    }

    @Test
    fun `exactly one of pattern and template is required`() {
        assertEquals(ErrorCode.INVALID_ARGUMENT, search(StructuralSearchArguments()).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, search(StructuralSearchArguments(pattern = "  ")).code())
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            search(StructuralSearchArguments(pattern = "x", template = "y")).code(),
        )
        val tooLong = "x".repeat(SearchStructurally.MAX_PATTERN_LENGTH + 1)
        assertEquals(ErrorCode.INVALID_ARGUMENT, search(StructuralSearchArguments(pattern = tooLong)).code())
        assertTrue(gateway.queries.isEmpty())
    }

    @Test
    fun `bad scopes and pages are refused before searching`() {
        assertEquals(ErrorCode.INVALID_ARGUMENT, search(StructuralSearchArguments(pattern = "x", scope = "all")).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, search(StructuralSearchArguments(pattern = "x", limit = 0)).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, search(StructuralSearchArguments(pattern = "x", offset = -1)).code())
        assertTrue(gateway.queries.isEmpty())
    }

    @Test
    fun `constraints are checked and -1 means no upper limit`() {
        matches(pattern(VariableConstraint("\$b\$", minCount = 0, maxCount = -1, text = "na.*", target = true)))
        val constraint = gateway.queries.single().constraints.single()
        assertEquals("b", constraint.name)
        assertEquals(VariableConstraint.UNBOUNDED, constraint.maxCount)
        assertEquals(0, constraint.minCount)
        assertEquals(true, constraint.target)
        for (bad in listOf(
            VariableConstraint("1a"),
            VariableConstraint("a", minCount = -1),
            VariableConstraint("a", maxCount = 0),
            VariableConstraint("a", minCount = 3, maxCount = 2),
            VariableConstraint("a", text = "(unclosed"),
            VariableConstraint("a", type = " "),
        )) {
            assertEquals(bad.toString(), ErrorCode.INVALID_ARGUMENT, search(pattern(bad)).code())
        }
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            search(pattern(VariableConstraint("a"), VariableConstraint("a", text = "x"))).code(),
        )
        assertEquals(1, gateway.queries.size)
    }

    @Test
    fun `gateway failures are passed on`() {
        gateway.failure = ToolResult.failure(ErrorCode.INVALID_ARGUMENT, "The IDE cannot use this search template")
        assertEquals(ErrorCode.INVALID_ARGUMENT, search(StructuralSearchArguments(pattern = "x")).code())
    }

    @Test
    fun `each action rejects the other action's parameters`() {
        assertEquals(ErrorCode.INVALID_ARGUMENT, templates(StructuralSearchArguments(pattern = "x")).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, templates(StructuralSearchArguments(scope = "project")).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, search(StructuralSearchArguments(pattern = "x", query = "q")).code())
    }

    @Test
    fun `templates are filtered by name or category and language, sorted and paged`() {
        gateway.templates = listOf(
            template("method calls", "Expressions", "Java"),
            template("All methods of a class", "Class-based", "Java"),
            template("Method calls", "Expressions", "Kotlin"),
            template("XML tag", "XML", "XML"),
            template("Mine", "User Defined", "Java", predefined = false),
        )
        assertEquals(5, foundTemplates().total)
        assertEquals(
            listOf("All methods of a class", "method calls", "Mine", "Method calls", "XML tag"),
            foundTemplates().page.items.map { it.name },
        )
        assertEquals(
            listOf("All methods of a class", "method calls"),
            foundTemplates(StructuralSearchArguments(query = "METHOD", fileType = "java")).page.items.map { it.name },
        )
        assertEquals(
            listOf("method calls", "Method calls"),
            foundTemplates(StructuralSearchArguments(query = "expressions")).page.items.map { it.name },
        )
        val page = foundTemplates(StructuralSearchArguments(limit = 2, offset = 2))
        assertEquals(listOf("Mine", "Method calls"), page.page.items.map { it.name })
        assertEquals(4, page.page.nextOffset)
        assertEquals(0, foundTemplates(StructuralSearchArguments(query = "nothing like this")).total)
    }

    @Test
    fun `template variables are read from the template text`() {
        assertEquals(listOf("Instance", "Arg"), TemplateVariables.namesIn("\$Instance\$.equals(\$Arg\$, \$Arg\$)"))
        assertTrue(TemplateVariables.namesIn("a.equals(b) && \$ 1 \$").isEmpty())
        val pattern = "\$a\$.equals(\$b\$)"
        assertTrue(TemplateVariables.checkConstrained(pattern, listOf(VariableConstraint("b"))) is ToolResult.Success)
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            TemplateVariables.checkConstrained(pattern, listOf(VariableConstraint("c"))).code(),
        )
    }

    private fun key(match: StructuralMatch) = "${match.site.location.path}:${match.site.location.line}"

    private fun template(name: String, category: String, fileType: String, predefined: Boolean = true) =
        SearchTemplate(name, category, fileType, "\$x\$", predefined, emptyList())
}
