package com.github.sinlordep.mcpcompanion.pullup.java

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.pullup.MemberPullUp
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassInitializer
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod

/**
 * What a pull-up out of [source] moves and where to: the members a request names and the superclass or interface it
 * means, checked as far as the IDE's dialog would not offer them. The IDE's conflict analysis checks the rest.
 * Call inside a read action.
 */
class JavaPullUpTargets(private val source: PsiClass) {

    /** [member] and the members named in [pullUp], all of [source]. */
    fun members(member: PsiMember, pullUp: MemberPullUp): ToolResult<List<PsiMember>> {
        val members = mutableListOf(member)
        for (name in pullUp.withMembers) {
            when (val named = named(name)) {
                is ToolResult.Success -> members += named.value
                is ToolResult.Failure -> return named
            }
        }
        return refusal(members.distinct(), pullUp.makeAbstract) ?: ToolResult.success(members.distinct())
    }

    /** The superclass or interface of [source] that [name] means (null: its superclass), writable and in Java. */
    fun target(name: String?): ToolResult<PsiClass> {
        val supertypes = supertypes()
        val target = if (name == null) defaultTarget(supertypes) else supertypes.matching(name)
        return when {
            target == null -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                if (name == null) {
                    "${source.name} has no single superclass in the project to pull up to."
                } else {
                    "$name is not a superclass or interface of ${source.name}."
                },
                "Pass targetClass; the project's supertypes of ${source.name}: " +
                    supertypes.filter(::inProject).joinToString { it.qualifiedName ?: it.name.orEmpty() }
                        .ifEmpty { "none" } + ".",
            )

            target.navigationElement.language != JavaLanguage.INSTANCE -> ToolResult.failure(
                ErrorCode.UNSUPPORTED,
                "${target.name} is not a Java class; mc_pull_up pulls up into Java classes and interfaces only.",
                "Pull up into a Java supertype, or edit the code yourself.",
            )

            !inProject(target) || !target.isWritable -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "${target.qualifiedName} is in a library, the SDK or a read-only file; members can only be pulled " +
                    "up into the project's own classes.",
                "Pass a supertype from the project's sources as targetClass.",
            )

            else -> ToolResult.success(target)
        }
    }

    /** The one field, method or nested class of [source] called [name]. */
    private fun named(name: String): ToolResult<PsiMember> {
        val named = source.fields.filter { it.name == name } +
            source.methods.filter { it.name == name && !it.isConstructor } +
            source.innerClasses.filter { it.name == name }
        return when (named.size) {
            0 -> ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "${source.name} has no field, method or nested class $name.",
                "withMembers takes simple names of members of the same class.",
            )

            1 -> ToolResult.success(named.single())

            else -> ToolResult.failure(
                ErrorCode.AMBIGUOUS,
                "${source.name} has ${named.size} members named $name (overloads).",
                "Pull overloaded methods up one at a time, pointing at each by position.",
            )
        }
    }

    private fun refusal(members: List<PsiMember>, makeAbstract: Boolean): ToolResult<Nothing>? {
        val wrong = members.firstOrNull {
            it is PsiClassInitializer || it is PsiEnumConstant || (it is PsiMethod && it.isConstructor)
        }
        return when {
            wrong != null -> ToolResult.failure(
                ErrorCode.UNSUPPORTED,
                "${wrong.name ?: "An initializer"} cannot be pulled up: constructors, initializers and enum " +
                    "constants stay in their class.",
                "Pull up fields, methods or nested classes.",
            )

            makeAbstract && members.none { it is PsiMethod } -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "makeAbstract applies to methods; none of the members is one.",
                "Leave makeAbstract out for fields and nested classes.",
            )

            else -> null
        }
    }

    /** Every superclass and interface of [source], nearest first, without `java.lang.Object`. */
    private fun supertypes(): List<PsiClass> {
        val found = LinkedHashSet<PsiClass>()
        var level = source.supers.toList()
        while (level.isNotEmpty()) {
            level = level.filter { it.qualifiedName != OBJECT && found.add(it) }.flatMap { it.supers.toList() }
        }
        return found.toList()
    }

    private fun defaultTarget(supertypes: List<PsiClass>): PsiClass? =
        source.superClass?.takeIf { it.qualifiedName != OBJECT && inProject(it) }
            ?: supertypes.filter { it in source.interfaces && inProject(it) }.singleOrNull()

    private fun List<PsiClass>.matching(name: String): PsiClass? =
        firstOrNull { it.qualifiedName == name } ?: filter { it.name == name }.singleOrNull()

    private fun inProject(type: PsiClass): Boolean {
        val file = type.navigationElement.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(type.project).isInContent(file)
    }

    private companion object {
        const val OBJECT = "java.lang.Object"
    }
}
