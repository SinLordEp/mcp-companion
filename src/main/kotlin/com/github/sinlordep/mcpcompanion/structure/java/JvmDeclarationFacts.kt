package com.github.sinlordep.mcpcompanion.structure.java

import com.github.sinlordep.mcpcompanion.structure.psi.DeclarationFacts
import com.github.sinlordep.mcpcompanion.structure.psi.DeclarationFactsProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiModifier
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.UEnumConstant
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UastVisibility
import org.jetbrains.uast.toUElement

/**
 * Kinds and modifiers of JVM declarations (Java, Kotlin, ...) through UAST, so that both languages report the same
 * kinds. Registered in `mcp-companion-java.xml`. Kotlin's `internal` reads as public and its `object` as class (UAST's
 * view).
 */
class JvmDeclarationFacts : DeclarationFactsProvider {

    override fun facts(element: PsiElement): DeclarationFacts? {
        val declaration = element.toUElement() as? UDeclaration ?: return null
        val kind = kindOf(declaration) ?: return null
        return DeclarationFacts(kind, modifiersOf(declaration))
    }

    private fun kindOf(declaration: UDeclaration): String? = when (declaration) {
        is UClass -> classKind(declaration)
        is UMethod -> if (declaration.isConstructor) "constructor" else "method"
        is UEnumConstant -> "enum constant"
        is UField -> "field"
        else -> null
    }

    private fun classKind(declaration: UClass): String = when {
        declaration.isAnnotationType -> "annotation"
        declaration.isInterface -> "interface"
        declaration.isEnum -> "enum"
        declaration.isRecord -> "record"
        else -> "class"
    }

    /** Visibility first, then static, abstract (not of interfaces themselves) and final (fields only). */
    private fun modifiersOf(declaration: UDeclaration): List<String> = listOfNotNull(
        VISIBILITY[declaration.visibility],
        PsiModifier.STATIC.takeIf { declaration.isStatic },
        PsiModifier.ABSTRACT.takeIf {
            !(declaration is UClass && declaration.isInterface) && declaration.hasModifierProperty(PsiModifier.ABSTRACT)
        },
        PsiModifier.FINAL.takeIf { declaration is UField && declaration.isFinal },
    )

    private companion object {
        val VISIBILITY: Map<UastVisibility, String> = mapOf(
            UastVisibility.PUBLIC to PsiModifier.PUBLIC,
            UastVisibility.PROTECTED to PsiModifier.PROTECTED,
            UastVisibility.PRIVATE to PsiModifier.PRIVATE,
            UastVisibility.PACKAGE_LOCAL to "package-private",
            UastVisibility.LOCAL to "local",
        )
    }
}
