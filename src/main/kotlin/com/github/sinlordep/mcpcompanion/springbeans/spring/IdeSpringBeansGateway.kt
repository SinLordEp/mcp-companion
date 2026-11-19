package com.github.sinlordep.mcpcompanion.springbeans.spring

import com.github.sinlordep.mcpcompanion.common.paging.mapNotNull
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.springbeans.BeanKind
import com.github.sinlordep.mcpcompanion.springbeans.BeanQuery
import com.github.sinlordep.mcpcompanion.springbeans.BeanSelector
import com.github.sinlordep.mcpcompanion.springbeans.BeanSummary
import com.github.sinlordep.mcpcompanion.springbeans.FoundBeans
import com.github.sinlordep.mcpcompanion.springbeans.IncomingInjection
import com.github.sinlordep.mcpcompanion.springbeans.InjectionPoint
import com.github.sinlordep.mcpcompanion.springbeans.PickedBeans
import com.github.sinlordep.mcpcompanion.springbeans.RawDependencies
import com.github.sinlordep.mcpcompanion.springbeans.SpringAnswer
import com.github.sinlordep.mcpcompanion.springbeans.SpringBean
import com.github.sinlordep.mcpcompanion.springbeans.SpringBeansGateway
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.spring.CommonSpringModel
import com.intellij.spring.model.SpringModelSearchParameters
import com.intellij.spring.model.utils.SpringModelSearchers

/**
 * The IDE's Spring model ([SpringModels]) for `mc_spring_beans`. Read-only; nothing is opened or shown. While the IDE
 * indexes, the answer says so at once instead of waiting.
 */
internal class IdeSpringBeansGateway(
    private val project: Project,
    private val models: SpringModels,
    private val selection: BeanSelection,
    private val descriptions: BeanDescriptions,
    private val injections: InjectionPoints,
) : SpringBeansGateway {

    override suspend fun beans(
        query: BeanQuery,
        pick: (List<BeanSummary>) -> ToolResult<PickedBeans>,
    ): ToolResult<SpringAnswer<FoundBeans>> = whenSmart(query.module) { modules ->
        val found = models.of(modules)
        if (found.isEmpty()) {
            ToolResult.success(SpringAnswer.NoSpringModel(query.module))
        } else {
            typeFilter(query.type).flatMap { type ->
                val ofType = type?.let(::ofType)
                val beans = models.beansOf(found).filter { ofType == null || ofType(it) }
                val byId = beans.associate { descriptions.idOf(it.pointer) to it.pointer }
                pick(beans.map { descriptions.summaryOf(it.pointer) }).map { picked ->
                    val described = picked.page.mapNotNull { byId[it.id]?.let(descriptions::describe) }
                    SpringAnswer.Ready(FoundBeans(picked.total, described, picked.hiddenLibraryBeans))
                }
            }
        }
    }

    override suspend fun dependencies(
        selector: BeanSelector,
        module: String?,
    ): ToolResult<SpringAnswer<RawDependencies>> = whenSmart(module) { modules ->
        val found = models.of(modules)
        if (found.isEmpty()) {
            ToolResult.success(SpringAnswer.NoSpringModel(module))
        } else {
            val all = models.beansOf(found)
            selection.select(selector, all).map { SpringAnswer.Ready(dependenciesOf(it, all)) }
        }
    }

    /**
     * Runs [read] over the modules [module] names in a read action in smart mode. An unknown module is `NOT_FOUND` at
     * once; while the IDE indexes, the answer is [SpringAnswer.Indexing] at once instead of waiting.
     */
    private suspend fun <T> whenSmart(
        module: String?,
        read: (List<Module>) -> ToolResult<SpringAnswer<T>>,
    ): ToolResult<SpringAnswer<T>> {
        val known = models.modules(module)
        return when {
            known is ToolResult.Failure -> known
            DumbService.isDumb(project) -> ToolResult.success(SpringAnswer.Indexing)
            else -> smartReadAction(project) { models.modules(module).flatMap(read) }
        }
    }

    /** The class a type filter names; `NOT_FOUND` when the project and its libraries have none of that name. */
    private fun typeFilter(qualifiedName: String?): ToolResult<PsiClass?> {
        if (qualifiedName == null) return ToolResult.success(null)
        return models.findClass(qualifiedName)?.let { ToolResult.success(it) } ?: ToolResult.failure(
            ErrorCode.NOT_FOUND,
            "No class '$qualifiedName' in the project or its libraries.",
            "Give the fully qualified name of a class or interface, e.g. com.example.PaymentGateway.",
        )
    }

    /** Whether a bean is of [psiClass] or a subtype, as the IDE finds them in the bean's model (once per model). */
    private fun ofType(psiClass: PsiClass): (ModelBean) -> Boolean {
        val idsPerModel = HashMap<CommonSpringModel, Set<String>>()
        return { bean ->
            val ids = idsPerModel.getOrPut(bean.model) {
                val search = SpringModelSearchParameters.byClass(psiClass)
                if (search.canSearch()) {
                    SpringModelSearchers.findBeans(bean.model, search.withInheritors().effectiveBeanTypes())
                        .mapTo(HashSet(), descriptions::idOf)
                } else {
                    emptySet()
                }
            }
            descriptions.idOf(bean.pointer) in ids
        }
    }

    private fun dependenciesOf(target: ModelBean, all: List<ModelBean>): RawDependencies {
        val bean = descriptions.describe(target.pointer)
        val own = injections.of(target.pointer, target.model).map(::describe)
        val projectBeans = all.filter { descriptions.originOf(it.pointer) == FileOrigin.PROJECT }
        val scanned = projectBeans.take(MAX_SCANNED)
        val incoming = scanned.flatMap { other ->
            ProgressManager.checkCanceled()
            if (descriptions.idOf(other.pointer) == bean.id) return@flatMap emptyList()
            injections.of(other.pointer, other.model)
                .filter { point -> point.candidates.any { descriptions.idOf(it) == bean.id } }
                .map { point -> IncomingInjection(descriptions.describe(other.pointer), describe(point)) }
        }
        return RawDependencies(
            bean = bean,
            injections = own,
            injectedInto = incoming,
            scannedBeans = scanned.size,
            complete = scanned.size == projectBeans.size,
            notes = notes(bean, scanned.map { descriptions.kindOf(it.pointer) }, scanned.size < projectBeans.size),
        )
    }

    private fun notes(bean: SpringBean, scannedKinds: List<BeanKind>, cut: Boolean): List<String> = buildList {
        if (bean.kind == BeanKind.XML) add(XML_TARGET)
        if (BeanKind.XML in scannedKinds) add(XML_OTHERS)
        if (cut) add("Only the first $MAX_SCANNED beans of the project were searched for injections of this bean.")
    }

    private fun describe(point: RawInjectionPoint): InjectionPoint = InjectionPoint(
        kind = point.kind,
        member = point.member,
        name = point.name,
        type = point.type,
        location = descriptions.locationOf(point.element),
        qualifier = point.qualifier,
        required = point.required,
        all = point.all,
        candidates = point.candidates.distinctBy(descriptions::idOf).map(descriptions::describe),
        source = point.source,
    )

    companion object {
        /** Beans of the project searched for injections of the selected bean, at most. */
        const val MAX_SCANNED: Int = 2_000

        private const val XML_TARGET = "This bean is defined in XML: its <property> and <constructor-arg> " +
            "references and autowire attributes are not read; only annotated injection points of its class are shown."
        private const val XML_OTHERS = "<property> and <constructor-arg> references of XML beans are not read, so " +
            "XML beans that receive this bean that way are missing from injectedInto."
    }
}
