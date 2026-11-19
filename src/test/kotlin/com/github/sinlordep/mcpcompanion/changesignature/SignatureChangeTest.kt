package com.github.sinlordep.mcpcompanion.changesignature

import com.github.sinlordep.mcpcompanion.common.refactoring.Visibility
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.move.ClassDestination
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.code
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Validation of the refactoring requests that does not need the IDE. */
class SignatureChangeTest {

    private fun keep(from: String) = ParameterChange(null, null, ParameterRef.parse(from), null)

    private fun new(name: String?, type: String?, default: String?) = ParameterChange(name, type, null, default)

    @Test
    fun `from is a position when numeric, otherwise a name`() {
        assertEquals(ParameterRef.Position(2), ParameterRef.parse("2"))
        assertEquals(ParameterRef.Name("count"), ParameterRef.parse("count"))
    }

    @Test
    fun `a valid change keeps its parts and drops blanks`() {
        val change = SignatureChange.of(
            listOf(keep("a"), ParameterChange(" b ", " int ", null, " 0 ")),
            returnType = " ",
            visibility = Visibility.PRIVATE,
        ).value()
        assertEquals(ParameterChange("b", "int", null, "0"), change.parameters!![1])
        assertNull(change.returnType)
        assertEquals(Visibility.PRIVATE, change.visibility)
    }

    @Test
    fun `invalid changes`() {
        val invalid = listOf(
            SignatureChange.of(null, null, null),
            SignatureChange.of(listOf(new("x", "int", null)), null, null),
            SignatureChange.of(listOf(new("x", null, "1")), null, null),
            SignatureChange.of(listOf(new(null, "int", "1")), null, null),
            SignatureChange.of(listOf(ParameterChange(null, null, ParameterRef.Name("a"), "1")), null, null),
            SignatureChange.of(listOf(keep("a"), keep("a")), null, null),
            SignatureChange.of(listOf(keep("-1")), null, null),
            SignatureChange.of(
                listOf(new("x", "int", "1"), ParameterChange("x", null, ParameterRef.Position(0), null)),
                null,
                null,
            ),
        )
        invalid.forEach { assertEquals(it.toString(), ErrorCode.INVALID_ARGUMENT, it.code()) }
    }

    @Test
    fun `an empty parameter list is a change`() {
        assertEquals(emptyList<ParameterChange>(), SignatureChange.of(emptyList(), null, null).value().parameters)
    }

    @Test
    fun `class destinations`() {
        assertEquals("com.example", ClassDestination.of(" com.example ", " ").value().targetPackage)
        assertNull(ClassDestination.of("a", null).value().sourceRoot)
        assertEquals("src/test", ClassDestination.of("a", "src/test").value().sourceRoot)
        listOf(null, " ", "a..b", "1a", "a b", ".a").forEach {
            assertEquals(it.toString(), ErrorCode.INVALID_ARGUMENT, ClassDestination.of(it, null).code())
        }
    }
}
