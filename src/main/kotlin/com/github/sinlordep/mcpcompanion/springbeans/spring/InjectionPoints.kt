package com.github.sinlordep.mcpcompanion.springbeans.spring

import com.github.sinlordep.mcpcompanion.springbeans.CandidateSource
import com.github.sinlordep.mcpcompanion.springbeans.InjectionKind
import com.github.sinlordep.mcpcompanion.springbeans.InjectionPoint
import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.spring.CommonSpringModel
import com.intellij.spring.model.SpringBeanPointer
import com.intellij.spring.model.SpringModelSearchParameters
import com.intellij.spring.model.jam.JamPsiClassSpringBean
import com.intellij.spring.model.jam.JamPsiMethodSpringBean
import com.intellij.spring.model.utils.SpringAutowireUtils
import com.intellij.spring.model.utils.SpringModelSearchers

/**
 * An injection point before its candidates are described: what [InjectionPoint] holds, with the candidates as the
 * IDE's bean pointers and the element whose place is reported.
 */
internal class RawInjectionPoint(
    val kind: InjectionKind,
    val member: String,
    val name: String,
    val type: String,
    val element: PsiElement,
    val qualifier: String?,
    val required: Boolean,
    val all: Boolean,
    val candidates: List<SpringBeanPointer<*>>,
    val source: CandidateSource,
)

/**
 * Finds where a bean receives other beans, through public API of the Spring plugin:
 *
 * - annotated fields and methods (`@Autowired`, `@Inject`, `@Resource`, ... as `SpringAutowireUtils` sees them) of the
 *   bean's class and its superclasses, with the candidates of the IDE's own autowiring analysis
 *   (`getAutowireAnnotationProperties`);
 * - the parameters of the constructor Spring calls for a component (the `@Autowired` one, or the only one) and of a
 *   `@Bean` method: the IDE offers no public candidate list for them (its constructor handler lives in the internal
 *   microservices module), so each parameter's candidates are the model's beans of its type and subtypes
 *   (`SpringModelSearchers.findBeans`), as for an annotated member the IDE does not resolve.
 *
 * A collection, array or `Map<String, T>` takes every bean of `T`; `Optional`, `ObjectProvider`, `ObjectFactory` and
 * `Provider` look for `T` and are optional. `@Value` parameters inject values, not beans, and are left out. Injections
 * written in XML are not read. Call inside a read action.
 */
internal class InjectionPoints {

    private val autowire: SpringAutowireUtils get() = SpringAutowireUtils.getInstance()

    fun of(pointer: SpringBeanPointer<*>, model: CommonSpringModel): List<RawInjectionPoint> {
        val bean = pointer.springBean
        val known = autowire.getAutowireAnnotationProperties(bean, model)
        val points = ArrayList<RawInjectionPoint>()
        pointer.beanClass?.let { points += annotatedMembers(it, known, model, pointer) }
        when (bean) {
            is JamPsiMethodSpringBean -> points += parameters(
                bean.psiElement,
                InjectionKind.BEAN_METHOD_PARAMETER,
                model,
                pointer,
            )

            is JamPsiClassSpringBean -> injectionConstructor(bean.psiElement)?.let {
                points += parameters(it, InjectionKind.CONSTRUCTOR_PARAMETER, model, pointer)
            }
        }
        return points
    }

    private fun annotatedMembers(
        psiClass: PsiClass,
        known: Map<PsiMember, Set<SpringBeanPointer<*>>>,
        model: CommonSpringModel,
        self: SpringBeanPointer<*>,
    ): List<RawInjectionPoint> {
        val fields = psiClass.allFields.filter { autowire.isAutowiredByAnnotation(it) }.mapNotNull { field ->
            ProgressManager.checkCanceled()
            point(
                kind = InjectionKind.FIELD,
                member = field.name,
                name = field.name,
                owner = field,
                declared = field.type,
                element = field.nameIdentifier,
                candidates = known[field]?.toList() ?: candidatesOf(InjectedType.of(field.type), model),
                source = if (known.containsKey(field)) CandidateSource.IDE else CandidateSource.TYPE,
                self = self,
            )
        }
        val methods = psiClass.allMethods
            .filter { !it.isConstructor && autowire.isAutowiredByAnnotation(it) }
            .flatMap { method -> methodPoints(method, known, model, self) }
        return fields + methods
    }

    /** A one-parameter method (a setter) is one point with the IDE's candidates; other methods one per parameter. */
    private fun methodPoints(
        method: PsiMethod,
        known: Map<PsiMember, Set<SpringBeanPointer<*>>>,
        model: CommonSpringModel,
        self: SpringBeanPointer<*>,
    ): List<RawInjectionPoint> {
        val parameter = method.parameterList.parameters.singleOrNull()
        val setter = parameter != null && method.name.startsWith(SET) && method.name.length > SET.length
        val kind = if (setter) InjectionKind.SETTER else InjectionKind.METHOD
        val ideCandidates = known[method]
        if (parameter == null || ideCandidates == null) return parameters(method, kind, model, self)
        return listOfNotNull(
            point(
                kind = kind,
                member = method.name,
                name = parameter.name,
                owner = method,
                declared = parameter.type,
                element = method.nameIdentifier ?: method,
                candidates = ideCandidates.toList(),
                source = CandidateSource.IDE,
                self = self,
                parameter = parameter,
            ),
        )
    }

    private fun parameters(
        method: PsiMethod,
        kind: InjectionKind,
        model: CommonSpringModel,
        self: SpringBeanPointer<*>,
    ): List<RawInjectionPoint> = method.parameterList.parameters
        .filterNot { AnnotationUtil.isAnnotated(it, VALUE, 0) }
        .mapNotNull { parameter ->
            ProgressManager.checkCanceled()
            point(
                kind = kind,
                member = if (method.isConstructor) method.containingClass?.name ?: method.name else method.name,
                name = parameter.name,
                owner = method,
                declared = parameter.type,
                element = parameter.nameIdentifier ?: parameter,
                candidates = candidatesOf(InjectedType.of(parameter.type), model),
                source = CandidateSource.TYPE,
                self = self,
                parameter = parameter,
            )
        }

    @Suppress("LongParameterList") // One point is many facts; a builder would only move them.
    private fun point(
        kind: InjectionKind,
        member: String,
        name: String,
        owner: PsiModifierListOwner,
        declared: PsiType,
        element: PsiElement?,
        candidates: List<SpringBeanPointer<*>>,
        source: CandidateSource,
        self: SpringBeanPointer<*>,
        parameter: PsiParameter? = null,
    ): RawInjectionPoint? {
        val place = element ?: return null
        val unwrapped = InjectedType.of(declared)
        return RawInjectionPoint(
            kind = kind,
            member = member,
            name = name,
            type = declared.presentableText,
            element = place,
            qualifier = parameter?.let(::qualifierOf) ?: qualifierOf(owner),
            required = !unwrapped.optional && requiredOf(owner),
            all = unwrapped.all,
            candidates = candidates.filter { it.isValid && !it.isReferenceTo(self.springBean) },
            source = source,
        )
    }

    /** The constructor Spring calls: the one marked for autowiring, or the only one when it takes parameters. */
    private fun injectionConstructor(psiClass: PsiClass): PsiMethod? {
        val constructors = psiClass.constructors
        return constructors.singleOrNull { autowire.isAutowiredByAnnotation(it) }
            ?: constructors.singleOrNull()?.takeIf { it.parameterList.parametersCount > 0 }
    }

    private fun candidatesOf(type: InjectedType, model: CommonSpringModel): List<SpringBeanPointer<*>> {
        val search = SpringModelSearchParameters.byType(type.type)
        if (!search.canSearch()) return emptyList()
        return SpringModelSearchers.findBeans(model, search.withInheritors().effectiveBeanTypes())
    }

    /** The value of `@Qualifier`/`@Named` on [owner], if any. */
    private fun qualifierOf(owner: PsiModifierListOwner): String? = AnnotationUtil.findAnnotation(owner, QUALIFIERS)
        ?.let { AnnotationUtil.getStringAttributeValue(it, "value") }
        ?.takeIf { it.isNotBlank() }

    /** False for `@Autowired(required = false)`. */
    private fun requiredOf(owner: PsiModifierListOwner): Boolean = AnnotationUtil.findAnnotation(owner, AUTOWIRED)
        ?.let { AnnotationUtil.getBooleanAttributeValue(it, "required") } != false

    companion object {
        private const val SET = "set"
        private const val VALUE = "org.springframework.beans.factory.annotation.Value"
        private val AUTOWIRED = setOf("org.springframework.beans.factory.annotation.Autowired")
        private val QUALIFIERS = setOf(
            "org.springframework.beans.factory.annotation.Qualifier",
            "jakarta.inject.Named",
            "javax.inject.Named",
        )
    }
}
