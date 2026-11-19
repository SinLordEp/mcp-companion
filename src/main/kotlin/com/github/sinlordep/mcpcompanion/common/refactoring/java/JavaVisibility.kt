package com.github.sinlordep.mcpcompanion.common.refactoring.java

import com.github.sinlordep.mcpcompanion.common.refactoring.Visibility
import com.intellij.psi.PsiModifier

/** The Java modifier of this visibility, as the IDE's refactorings take it (`PsiModifier.PACKAGE_LOCAL` for none). */
val Visibility.javaModifier: String
    get() = when (this) {
        Visibility.PUBLIC -> PsiModifier.PUBLIC
        Visibility.PROTECTED -> PsiModifier.PROTECTED
        Visibility.PACKAGE_PRIVATE -> PsiModifier.PACKAGE_LOCAL
        Visibility.PRIVATE -> PsiModifier.PRIVATE
    }
