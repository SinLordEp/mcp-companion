package com.github.sinlordep.mcpcompanion.springbeans.spring

import com.intellij.psi.CommonClassNames
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType

/**
 * The bean type an injection point looks for ([type]), behind the containers Spring understands: a collection, an
 * array or a `Map<String, T>` takes every bean of `T` ([all]); `Optional`, `ObjectProvider`, `ObjectFactory` and
 * `Provider` look for `T` and need none ([optional]). Call inside a read action.
 */
internal class InjectedType private constructor(
    val type: PsiType,
    val all: Boolean = false,
    val optional: Boolean = false,
) {
    companion object {
        fun of(declared: PsiType): InjectedType = when (declared) {
            is PsiArrayType -> InjectedType(declared.componentType, all = true)
            is PsiClassType -> container(declared) ?: InjectedType(declared)
            else -> InjectedType(declared)
        }

        private fun container(type: PsiClassType): InjectedType? {
            val raw = type.resolve()?.qualifiedName ?: return null
            val arguments = type.parameters.map(::bound)
            return when (raw) {
                in COLLECTIONS -> elementOf(arguments, all = true)
                in PROVIDERS -> elementOf(arguments, optional = true)
                in MAPS -> mapValueOf(arguments)
                else -> null
            }
        }

        private fun bound(type: PsiType): PsiType = if (type is PsiWildcardType) type.extendsBound else type

        private fun elementOf(arguments: List<PsiType>, all: Boolean = false, optional: Boolean = false) =
            arguments.singleOrNull()?.let { InjectedType(it, all, optional) }

        /** The value type of a `Map<String, T>`: Spring injects the beans of `T` by name. */
        private fun mapValueOf(arguments: List<PsiType>) =
            arguments.takeIf { it.size == 2 && it[0].equalsToText(STRING) }?.let { InjectedType(it[1], all = true) }

        private const val STRING = CommonClassNames.JAVA_LANG_STRING
        private val COLLECTIONS = setOf(
            CommonClassNames.JAVA_UTIL_COLLECTION,
            CommonClassNames.JAVA_UTIL_LIST,
            CommonClassNames.JAVA_UTIL_SET,
            "java.util.SortedSet",
            "java.util.NavigableSet",
        )
        private val MAPS = setOf(CommonClassNames.JAVA_UTIL_MAP, "java.util.SortedMap", "java.util.NavigableMap")
        private val PROVIDERS = setOf(
            CommonClassNames.JAVA_UTIL_OPTIONAL,
            "org.springframework.beans.factory.ObjectProvider",
            "org.springframework.beans.factory.ObjectFactory",
            "jakarta.inject.Provider",
            "javax.inject.Provider",
        )
    }
}
