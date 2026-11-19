package com.github.sinlordep.mcpcompanion.changesignature.java

import com.github.sinlordep.mcpcompanion.changesignature.ParameterChange
import com.github.sinlordep.mcpcompanion.changesignature.ParameterRef
import com.github.sinlordep.mcpcompanion.changesignature.SignatureChange
import com.github.sinlordep.mcpcompanion.common.refactoring.java.javaModifier
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameHelper
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import com.intellij.refactoring.changeSignature.ParameterInfoImpl
import com.intellij.util.IncorrectOperationException
import com.intellij.util.VisibilityUtil

/**
 * A [SignatureChange] resolved against a Java [method]: parameter infos for the IDE's processor, the return type and
 * the visibility modifier, with every type and default value parsed in the method's context. Call inside a read
 * action.
 */
class JavaSignature(private val method: PsiMethod) {

    /** The processor's view of the new signature. */
    class Resolved(
        val parameters: Array<ParameterInfoImpl>,
        val returnType: PsiType?,
        val visibility: String,
        val isUnchanged: Boolean,
    )

    private class Invalid(message: String) : Exception(message)

    private val factory = JavaPsiFacade.getElementFactory(method.project)
    private val names = PsiNameHelper.getInstance(method.project)
    private val current = method.parameterList.parameters

    fun resolve(change: SignatureChange): ToolResult<Resolved> = try {
        val parameters = change.parameters?.mapIndexed(::parameter)
            ?: current.indices.map {
                ParameterInfoImpl.create(it).withName(current[it].name).withType(current[it].type)
            }
        val kept = parameters.map { it.oldIndex }.filter { it >= 0 }
        kept.firstOrNull { index -> kept.count { it == index } > 1 }
            ?.let { throw Invalid("The current parameter at $it is used twice.") }
        val returnType = change.returnType?.let(::returnType) ?: method.returnType
        val visibility = change.visibility?.javaModifier ?: VisibilityUtil.getVisibilityModifier(method.modifierList)
        ToolResult.success(
            Resolved(
                parameters.toTypedArray(),
                returnType,
                visibility,
                isUnchanged(parameters, returnType, visibility),
            ),
        )
    } catch (e: Invalid) {
        ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            e.message.orEmpty(),
            "Types are Java types as in source (qualified names for classes that are not imported), default " +
                "values Java expressions; from is a current parameter's name or 0-based position.",
        )
    }

    private fun parameter(position: Int, change: ParameterChange): ParameterInfoImpl {
        val from = change.from?.let { indexOf(it, position) }
        val name = change.name ?: current[checkNotNull(from)].name
        if (!names.isIdentifier(name)) throw Invalid("parameters[$position]: '$name' is not a Java identifier.")
        val type = change.type?.let { type(it, "parameters[$position].type") } ?: current[checkNotNull(from)].type
        return if (from == null) {
            val value = checkNotNull(change.defaultValue)
            parse("parameters[$position].defaultValue", value) { factory.createExpressionFromText(value, method) }
            ParameterInfoImpl.createNew().withName(name).withType(type).withDefaultValue(value)
        } else {
            ParameterInfoImpl.create(from).withName(name).withType(type)
        }
    }

    private fun indexOf(ref: ParameterRef, position: Int): Int = when (ref) {
        is ParameterRef.Position ->
            ref.index.takeIf { it < current.size }
                ?: throw Invalid(
                    "parameters[$position]: ${method.name} has ${current.size} parameter(s); no ${ref.index}.",
                )

        is ParameterRef.Name -> current.indexOfFirst { it.name == ref.name }.takeIf { it >= 0 }
            ?: throw Invalid(
                "parameters[$position]: ${method.name} has no parameter ${ref.name} " +
                    "(it has: ${current.joinToString { it.name }}).",
            )
    }

    private fun returnType(text: String): PsiType {
        if (method.isConstructor) throw Invalid("A constructor has no return type.")
        return type(text, "returnType")
    }

    private fun type(text: String, what: String): PsiType {
        val type = parse(what, text) { factory.createTypeFromText(text, method) }
        unresolved(type)?.let { throw Invalid("$what: '$it' is not a known type here.") }
        return type
    }

    /** The first class name in [type] that does not resolve, or null. */
    private fun unresolved(type: PsiType): String? = when (type) {
        is PsiArrayType -> unresolved(type.componentType)

        is PsiWildcardType -> type.bound?.let(::unresolved)

        is PsiClassType -> if (type.resolve() == null) {
            type.className
        } else {
            type.parameters.firstNotNullOfOrNull(::unresolved)
        }

        else -> null
    }

    private fun <T> parse(what: String, text: String, parser: () -> T): T = try {
        parser()
    } catch (_: IncorrectOperationException) {
        throw Invalid("$what: '$text' does not parse.")
    }

    private fun isUnchanged(parameters: List<ParameterInfoImpl>, returnType: PsiType?, visibility: String): Boolean =
        parameters.size == current.size &&
            parameters.withIndex().all { (index, info) ->
                info.oldIndex == index && info.name == current[index].name &&
                    info.createType(method, method.manager) == current[index].type
            } &&
            returnType == method.returnType &&
            visibility == VisibilityUtil.getVisibilityModifier(method.modifierList)
}
