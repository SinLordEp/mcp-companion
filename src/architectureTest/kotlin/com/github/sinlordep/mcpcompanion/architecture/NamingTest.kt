package com.github.sinlordep.mcpcompanion.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoObjectDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

/** Class rules of docs/PLAN.md section 5.1, checked on the production sources. */
class NamingTest {

    private val production = Konsist.scopeFromProduction()

    @Test
    fun `no grab-bag classes`() {
        val grabBag = Regex(".*(Utils?|Helpers?|Managers?)$")
        production.classesAndInterfacesAndObjects().assertFalse { grabBag.matches(it.name) }
    }

    @Test
    fun `no global mutable state in top-level or object properties`() {
        production.properties().filter { it.isTopLevel || it.containingDeclaration is KoObjectDeclaration }
            .assertFalse { it.isVar }
    }
}
