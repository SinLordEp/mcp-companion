package com.github.sinlordep.mcpcompanion.changesignature.java

import com.github.sinlordep.mcpcompanion.McpCompanionBundle
import com.github.sinlordep.mcpcompanion.changesignature.SignatureChange
import com.github.sinlordep.mcpcompanion.changesignature.psi.SignatureChangeSupport
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PreparedRefactoring
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.refactoring.changeSignature.ChangeInfo
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessor
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessorBase
import com.intellij.refactoring.changeSignature.ChangeSignatureUsageProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

/**
 * Changes signatures of Java methods and constructors with the IDE's Change Signature (`ChangeSignatureProcessor`),
 * registered in `mcp-companion-java.xml`.
 *
 * Usages and conflicts come from the public static parts of the processor and from every language's
 * `ChangeSignatureUsageProcessor` (so Kotlin call sites of a Java method are found and rewritten by the Kotlin
 * plugin). The processor's `preprocessUsages`, which asks the user (default values, weaker visibility of overriding
 * methods, covariant return types), is never run; the rules instead: new parameters always come with a default
 * value; overriding methods keep their visibility; overriding methods get the new return type. A method that
 * overrides or implements another is a conflict, as in `mc_safe_delete`: change the topmost declaration, and the
 * methods overriding it change with it.
 */
class JavaSignatureChangeSupport : SignatureChangeSupport {

    override fun prepare(declaration: PsiElement, change: SignatureChange): ToolResult<PreparedRefactoring>? {
        val method = declaration as? PsiMethod ?: return null
        if (method.language != JavaLanguage.INSTANCE) return null
        return JavaSignature(method).resolve(change).flatMap { signature ->
            if (signature.isUnchanged) {
                return@flatMap ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "${method.name} already has that signature; nothing to change.",
                    "Pass the parameters, returnType or visibility that should differ.",
                )
            }
            val processor = ChangeSignatureProcessor(
                method.project,
                method,
                false,
                signature.visibility,
                method.name,
                signature.returnType,
                signature.parameters,
            )
            val changeInfo: ChangeInfo = processor.changeInfo
            val usages = ChangeSignatureProcessorBase.findUsages(changeInfo)
            ToolResult.success(
                PreparedRefactoring(
                    usages = usages,
                    conflicts = conflictsOf(method, changeInfo, usages),
                    declarationFiles = listOfNotNull(method.containingFile?.virtualFile),
                    commandName = McpCompanionBundle.message("command.change.signature", method.name),
                ) { processor.executeEx(it) },
            )
        }
    }

    /** What every language's processor reports, name collisions, and the override rule (see the class comment). */
    private fun conflictsOf(
        method: PsiMethod,
        changeInfo: ChangeInfo,
        usages: Array<UsageInfo>,
    ): MultiMap<PsiElement, String> {
        val conflicts = MultiMap<PsiElement, String>()
        val ref = Ref(usages)
        for (processor in ChangeSignatureUsageProcessor.EP_NAME.extensionList) {
            processor.findConflicts(changeInfo, ref).entrySet().forEach { (element, messages) ->
                conflicts.putValues(element, messages)
            }
        }
        RenameUtil.addConflictDescriptions(usages, conflicts)
        for (topmost in method.findDeepestSuperMethods()) {
            val name = "${topmost.containingClass?.qualifiedName}#${topmost.name}"
            conflicts.putValue(
                topmost,
                "${method.name} overrides or implements $name; changing it alone would break the override. Change " +
                    "$name instead (methods overriding it change with it), if it is in the project.",
            )
        }
        return conflicts
    }
}
