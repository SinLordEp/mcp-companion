package com.github.sinlordep.mcpcompanion.common.symbols.java

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocation
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.github.sinlordep.mcpcompanion.common.symbols.psi.QualifiedNameLocatorProvider
import com.github.sinlordep.mcpcompanion.common.symbols.psi.symbolLocatorsFor
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Goes through [symbolLocatorsFor], so it also checks that `mcp-companion-java.xml` registers the locator. */
class JavaQualifiedNameLocatorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("a/Shapes.java", SHAPES)
    }

    private fun locate(name: String): ToolResult<LocatedSymbol> = timeoutRunBlocking {
        symbolLocatorsFor(project).locate(SymbolQuery.ByQualifiedName(name))
    }

    private fun located(name: String): LocatedSymbol = when (val result = locate(name)) {
        is ToolResult.Success -> result.value
        is ToolResult.Failure -> throw AssertionError("expected a symbol for $name but got ${result.error}")
    }

    private fun errorCode(name: String): ErrorCode = (locate(name) as ToolResult.Failure).error.code

    fun testJavaLocatorIsRegisteredThroughTheOptionalDescriptor() {
        assertTrue(QualifiedNameLocatorProvider.EP.extensionList.any { it is JavaQualifiedNameLocatorProvider })
    }

    fun testFindsClass() {
        val symbol = located("a.Shapes")
        assertEquals("a.Shapes", symbol.qualifiedName)
        assertEquals(SymbolLocation("a/Shapes.java", 2, 14, FileOrigin.PROJECT), symbol.location)
    }

    fun testFindsNestedClassByDottedNameAndAsMember() {
        assertEquals("a.Shapes.Circle", located("a.Shapes.Circle").qualifiedName)
        assertEquals("a.Shapes.Circle", located("a.Shapes#Circle").qualifiedName)
    }

    fun testFindsMethodAndField() {
        assertEquals(SymbolLocation("a/Shapes.java", 4, 26, FileOrigin.PROJECT), located("a.Shapes#area").location)
        assertEquals("count", located("a.Shapes#count").name)
    }

    fun testOverloadsAreAmbiguous() {
        assertEquals(ErrorCode.AMBIGUOUS, errorCode("a.Shapes#scale"))
    }

    fun testUnknownNamesAreNotFoundAndEmptyIsInvalid() {
        assertEquals(ErrorCode.NOT_FOUND, errorCode("a.Nope"))
        assertEquals(ErrorCode.NOT_FOUND, errorCode("a.Shapes#nope"))
        assertEquals(ErrorCode.INVALID_ARGUMENT, errorCode("  "))
    }

    private companion object {
        val SHAPES = """
            package a;
            public class Shapes {
                int count;
                public static double area(double r) { return Math.PI * r * r; }
                public double scale(double f) { return f; }
                public double scale(double f, double g) { return f * g; }
                public static class Circle {}
            }
        """.trimIndent()
    }
}
