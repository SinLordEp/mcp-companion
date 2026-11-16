package com.github.sinlordep.mcpcompanion.common.symbols.psi

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocation
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.guessProjectDir
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PositionSymbolLocatorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("a/Greeter.java", GREETER)
    }

    private fun locate(path: String, line: Int, column: Int): ToolResult<LocatedSymbol> = timeoutRunBlocking {
        symbolLocatorsFor(project).locate(SymbolQuery.AtPosition(path, line, column))
    }

    private fun located(path: String, line: Int, column: Int): LocatedSymbol =
        when (val result = locate(path, line, column)) {
            is ToolResult.Success -> result.value
            is ToolResult.Failure -> throw AssertionError("expected a symbol but got ${result.error}")
        }

    private fun errorCode(path: String, line: Int, column: Int): ErrorCode =
        (locate(path, line, column) as ToolResult.Failure).error.code

    fun testReferenceResolvesToItsDeclaration() {
        // line 4: `    public String twice(String n) { return greet(greet(n)); }` -- column 48 is inside `greet`
        val symbol = located("a/Greeter.java", 4, 48)
        assertEquals("greet", symbol.name)
        assertEquals(SymbolLocation("a/Greeter.java", 3, 19), symbol.location)
    }

    fun testDeclarationNameIsItsOwnSymbol() {
        val symbol = located("a/Greeter.java", 2, 15)
        assertEquals("Greeter", symbol.name)
        assertEquals("a.Greeter", symbol.qualifiedName)
        assertEquals(SymbolLocation("a/Greeter.java", 2, 14), symbol.location)
    }

    fun testHandleLeadsBackToThePlatformElement() = timeoutRunBlocking {
        val handle = located("a/Greeter.java", 4, 48).handle as PsiSymbolHandle
        val element = readAction { handle.element() }
        assertTrue(element is PsiMethod)
    }

    fun testWhitespaceIsNoSymbol() {
        assertEquals(ErrorCode.NOT_FOUND, errorCode("a/Greeter.java", 1, 11))
    }

    fun testPositionOutsideTheFileIsAnArgumentError() {
        assertEquals(ErrorCode.INVALID_ARGUMENT, errorCode("a/Greeter.java", 99, 1))
        assertEquals(ErrorCode.INVALID_ARGUMENT, errorCode("a/Greeter.java", 0, 1))
        assertEquals(ErrorCode.INVALID_ARGUMENT, errorCode("a/Greeter.java", 2, 0))
        assertEquals(ErrorCode.INVALID_ARGUMENT, errorCode("a/Greeter.java", 2, 500))
    }

    fun testAbsoluteAndDotPathsInsideTheProjectWork() {
        val root = checkNotNull(project.guessProjectDir()).path
        assertEquals("Greeter", located("$root/a/Greeter.java", 2, 15).name)
        assertEquals("Greeter", located("./a/../a/Greeter.java", 2, 15).name)
    }

    fun testPathsLeavingTheProjectAreNotResolvedInsideIt() {
        assertEquals(ErrorCode.NOT_FOUND, errorCode("../a/Greeter.java", 2, 15))
        assertEquals(ErrorCode.NOT_FOUND, errorCode("a/../../a/Greeter.java", 2, 15))
    }

    fun testMissingFileIsNotFound() {
        assertEquals(ErrorCode.NOT_FOUND, errorCode("a/Missing.java", 1, 1))
        assertEquals(ErrorCode.NOT_FOUND, errorCode("a", 1, 1))
    }

    private companion object {
        val GREETER = """
            package a;
            public class Greeter {
                public String greet(String name) { return "Hi " + name; }
                public String twice(String n) { return greet(greet(n)); }
            }
        """.trimIndent()
    }
}
