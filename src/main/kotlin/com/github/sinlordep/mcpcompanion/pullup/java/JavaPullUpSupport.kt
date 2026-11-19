package com.github.sinlordep.mcpcompanion.pullup.java

import com.github.sinlordep.mcpcompanion.McpCompanionBundle
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PreparedRefactoring
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.pullup.MemberPullUp
import com.github.sinlordep.mcpcompanion.pullup.psi.PullUpSupport
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.WriteAction
import com.intellij.pom.java.JavaFeature
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiUtil
import com.intellij.refactoring.JavaRefactoringSettings
import com.intellij.refactoring.memberPullUp.PullUpConflictsUtil
import com.intellij.refactoring.memberPullUp.PullUpHelper
import com.intellij.refactoring.memberPullUp.PullUpProcessor
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.classMembers.MemberInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

/**
 * Pulls members of Java classes up with the IDE's Pull Members Up (`PullUpProcessor`), registered in
 * `mcp-companion-java.xml`.
 *
 * Conflicts come from the public `PullUpConflictsUtil.checkConflicts`, which the IDE's dialog runs before it starts
 * the processor (members left behind that the moved ones use, accessibility from the target, abstract methods
 * the target's other subclasses would lack, a broken functional interface). Usages are the references to moved
 * static members, as the processor finds them; a reference outside Java code (Kotlin) is a conflict: the processor
 * would hand it to that language's pull-up helper, and Kotlin's only handles Kotlin classes (it fails on a Java target
 * class), while the reference through the subclass would no longer compile. The execution is the processor's own
 * public `moveMembersToBase` and `moveFieldInitializations` plus Java's `updateUsage`: what its `performRefactoring`
 * does, except the search for code duplicating the moved methods, which asks the user whether to replace each
 * duplicate (a dialog after the refactoring), and is left out. See `docs/notes-step12.md`.
 */
class JavaPullUpSupport : PullUpSupport {

    override fun prepare(declaration: PsiElement, pullUp: MemberPullUp): ToolResult<PreparedRefactoring>? {
        val member = declaration as? PsiMember ?: return null
        if (member.language != JavaLanguage.INSTANCE) return null
        val source = member.containingClass
            ?: return unsupported("${member.name ?: "This"} is not a member of a class; mc_pull_up moves members.")
        val targets = JavaPullUpTargets(source)
        return targets.members(member, pullUp).flatMap { members ->
            targets.target(pullUp.targetClass).map { target -> prepared(source, target, members, pullUp.makeAbstract) }
        }
    }

    private fun prepared(
        source: PsiClass,
        target: PsiClass,
        members: List<PsiMember>,
        makeAbstract: Boolean,
    ): PreparedRefactoring {
        // Into an interface of a language level without default methods, methods can only be declared abstract.
        val abstractOnly = target.isInterface && !PsiUtil.isAvailable(JavaFeature.EXTENSION_METHODS, target)
        val infos = members.map { member ->
            MemberInfo(member).apply {
                isChecked = true
                isToAbstract = member is PsiMethod && (makeAbstract || abstractOnly)
            }
        }.toTypedArray()
        val policy = DocCommentPolicy(JavaRefactoringSettings.getInstance().PULL_UP_MEMBERS_JAVADOC)
        val processor = PullUpProcessor(source, target, infos, policy)
        // As the processor's own search: references to static members get the target as their qualifier.
        val usages = members.filter { it.hasModifierProperty("static") }
            .flatMap { ReferencesSearch.search(it).findAll() }
            .map(::UsageInfo)
            .toTypedArray()
        return PreparedRefactoring(
            usages = usages,
            conflicts = conflictsOf(source, target, infos).apply { addForeignReferences(usages) },
            declarationFiles = listOfNotNull(source.containingFile?.virtualFile, target.containingFile?.virtualFile)
                .distinct(),
            commandName = McpCompanionBundle.message(
                "command.pull.up",
                members.joinToString { it.name.orEmpty() },
                target.name.orEmpty(),
            ),
        ) { found -> WriteAction.run<RuntimeException> { pullUp(processor, found) } }
    }

    /** The processor's `performRefactoring` without the search for duplicates (see the class comment). */
    private fun pullUp(processor: PullUpProcessor, usages: Array<UsageInfo>) {
        processor.moveMembersToBase()
        processor.moveFieldInitializations()
        val java = PullUpHelper.INSTANCE.forLanguage(JavaLanguage.INSTANCE)?.createPullUpHelper(processor) ?: return
        usages.mapNotNull { it.element }.filter { it.language == JavaLanguage.INSTANCE }.forEach(java::updateUsage)
    }

    /** References the IDE would not update (see the class comment). */
    private fun MultiMap<PsiElement, String>.addForeignReferences(usages: Array<UsageInfo>) {
        usages.mapNotNull { it.element }.filter { it.language != JavaLanguage.INSTANCE }.forEach { element ->
            putValue(
                element,
                "This ${element.language.displayName} reference to ${element.text} is not updated by the IDE and " +
                    "would no longer compile.",
            )
        }
    }

    private fun conflictsOf(
        source: PsiClass,
        target: PsiClass,
        infos: Array<MemberInfo>,
    ): MultiMap<PsiElement, String> {
        val conflicts = MultiMap<PsiElement, String>()
        val directory = target.containingFile?.containingDirectory ?: return conflicts
        val targetPackage = JavaDirectoryService.getInstance().getPackage(directory) ?: return conflicts
        conflicts.putAllValues(
            PullUpConflictsUtil.checkConflicts(infos, source, target, targetPackage, directory) { method ->
                PullUpProcessor.checkedInterfacesContain(infos.toList(), method)
            },
        )
        return conflicts
    }

    private fun unsupported(message: String): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.UNSUPPORTED,
        message,
        "Point at a field, method or nested class of a Java class.",
    )
}
