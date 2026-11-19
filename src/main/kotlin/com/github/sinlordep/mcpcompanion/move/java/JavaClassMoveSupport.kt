package com.github.sinlordep.mcpcompanion.move.java

import com.github.sinlordep.mcpcompanion.McpCompanionBundle
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PreparedRefactoring
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.move.psi.ClassMoveSupport
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiNameHelper
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.JavaRefactoringFactory
import com.intellij.refactoring.move.moveClassesOrPackages.MoveClassesOrPackagesProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewUtil
import com.intellij.util.containers.MultiMap

/**
 * Moves top-level Java classes with the IDE's Move Class refactoring (`JavaRefactoringFactory`), registered in
 * `mcp-companion-java.xml`. Usages come from the refactoring's public `findUsages`; conflicts from the processor's
 * public `detectConflicts` (accessibility after the move, module dependencies), which its interactive run would show
 * in a dialog. Comments, strings and non-Java files are not searched, as in the IDE's default.
 */
class JavaClassMoveSupport : ClassMoveSupport {

    override fun prepare(
        declaration: PsiElement,
        targetPackage: String,
        sourceRoot: VirtualFile?,
    ): ToolResult<PreparedRefactoring>? {
        val psiClass = declaration as? PsiClass ?: return null
        if (psiClass.language != JavaLanguage.INSTANCE) return null
        val file = psiClass.containingFile as? PsiJavaFile ?: return null
        val project = psiClass.project
        val currentRoot = file.virtualFile?.let { ProjectFileIndex.getInstance(project).getSourceRootForFile(it) }
        val root = sourceRoot ?: currentRoot
        refusal(psiClass, file, targetPackage, currentRoot, root)?.let { return it }
        checkNotNull(root)
        val samePackage = file.packageName == targetPackage
        val factory = JavaRefactoringFactory.getInstance(project)
        val destination = factory.createSourceRootMoveDestination(targetPackage, root)
        val refactoring = factory.createMoveClassesOrPackages(arrayOf(psiClass), destination, false, false)
        // As the processor does: in the same package no reference changes, only the file moves.
        val usages: Array<UsageInfo> =
            if (samePackage) UsageInfo.EMPTY_ARRAY else UsageViewUtil.removeDuplicatedUsages(refactoring.findUsages())
        val conflicts = MultiMap<PsiElement, String>()
        MoveClassesOrPackagesProcessor.detectConflicts(
            usages,
            conflicts,
            arrayOf(psiClass),
            destination.targetPackage,
            destination,
        )
        return ToolResult.success(
            PreparedRefactoring(
                usages,
                conflicts,
                listOfNotNull(file.virtualFile),
                McpCompanionBundle.message("command.move", psiClass.name.orEmpty(), targetPackage),
            ) { refactoring.doRefactoring(it) },
        )
    }

    private fun refusal(
        psiClass: PsiClass,
        file: PsiJavaFile,
        targetPackage: String,
        currentRoot: VirtualFile?,
        root: VirtualFile?,
    ): ToolResult<Nothing>? = when {
        psiClass.parent != file -> ToolResult.failure(
            ErrorCode.UNSUPPORTED,
            "${psiClass.name ?: "This class"} is not a top-level class; mc_move moves top-level classes only.",
            "Move its top-level class, or ask the user to run the IDE's Move Inner Class refactoring.",
        )

        !PsiNameHelper.getInstance(psiClass.project).isQualifiedName(targetPackage) -> ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "'$targetPackage' is not a valid Java package name (keywords are not allowed).",
            "Pass dot-separated Java identifiers.",
        )

        currentRoot == null || root == null -> ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "${file.name} is not in a source root, so it has no package to move from.",
            "Move classes of source or test source folders only.",
        )

        file.packageName == targetPackage && root == currentRoot -> ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "${psiClass.qualifiedName} is already in package $targetPackage of that source root.",
            "Pass another targetPackage or targetSourceRoot.",
        )

        else -> existing(psiClass, file, targetPackage, root)
    }

    /** A class of that name already in the target package (any source root), or a file of that name in the target. */
    private fun existing(
        psiClass: PsiClass,
        file: PsiJavaFile,
        targetPackage: String,
        root: VirtualFile,
    ): ToolResult<Nothing>? {
        val project = psiClass.project
        val qualifiedName = "$targetPackage.${psiClass.name}"
        val sameName = JavaPsiFacade.getInstance(project)
            .findClass(qualifiedName, GlobalSearchScope.projectScope(project))
            ?.takeIf { it != psiClass }
        val targetFile = root.findFileByRelativePath("${targetPackage.replace('.', '/')}/${file.name}")
        if (sameName == null && targetFile == null) return null
        return ToolResult.failure(
            ErrorCode.CONFLICT,
            "A class $qualifiedName or a file ${file.name} already exists in the target; moving would create a " +
                "duplicate. Nothing was changed.",
            "Rename or remove one of the classes first (rename_refactoring), or pick another targetPackage.",
        )
    }
}
