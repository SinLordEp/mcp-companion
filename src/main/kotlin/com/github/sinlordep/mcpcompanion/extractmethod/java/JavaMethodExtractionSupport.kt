package com.github.sinlordep.mcpcompanion.extractmethod.java

import com.github.sinlordep.mcpcompanion.McpCompanionBundle
import com.github.sinlordep.mcpcompanion.common.refactoring.java.javaModifier
import com.github.sinlordep.mcpcompanion.common.refactoring.psi.PreparedRefactoring
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.source.psi.positionsOf
import com.github.sinlordep.mcpcompanion.extractmethod.MethodExtraction
import com.github.sinlordep.mcpcompanion.extractmethod.psi.MethodExtractionSupport
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameHelper
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.extractMethod.newImpl.CodeFragmentAnalyzer
import com.intellij.refactoring.extractMethod.newImpl.ExtractException
import com.intellij.refactoring.extractMethod.newImpl.ExtractMethodPipeline
import com.intellij.refactoring.extractMethod.newImpl.ExtractSelector
import com.intellij.refactoring.extractMethod.newImpl.MethodExtractor
import com.intellij.refactoring.extractMethod.newImpl.structures.ExtractOptions
import com.intellij.refactoring.util.ConflictsUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

/**
 * Extracts Java code into a new method with the IDE's Extract Method (its current implementation,
 * `extractMethod.newImpl`), registered in `mcp-companion-java.xml`, through the public steps the IDE's own flow is
 * built from, without an editor, the in-place rename template or the dialog: `ExtractSelector` picks the statements or
 * the expression in the range as the IDE does for a selection; `ExtractMethodPipeline.findAllOptionsToExtract` infers
 * parameters, return value, exceptions and whether the method is static, and reports code it cannot extract (several
 * exits, an observable throw) as an `ExtractException` with the places, which become conflicts;
 * `ConflictsUtil.checkMethodConflicts` finds a method of that signature in the class, as the IDE's name check does;
 * `MethodExtractor.extractMethod` replaces the code with the call and adds the method. The method goes to the
 * innermost class that is not anonymous, as the IDE preselects. Not done: replacing duplicates of the code (the IDE
 * offers it afterwards in a popup). See `docs/notes-step12.md`.
 */
class JavaMethodExtractionSupport : MethodExtractionSupport {

    override fun prepare(
        file: PsiFile,
        range: TextRange,
        extraction: MethodExtraction,
    ): ToolResult<PreparedRefactoring>? {
        if (file !is PsiJavaFile || file.language != JavaLanguage.INSTANCE) return null
        if (!PsiNameHelper.getInstance(file.project).isIdentifier(extraction.methodName)) {
            return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "'${extraction.methodName}' is not a Java identifier (keywords are not allowed).",
                "Pass a method name such as computeTotal.",
            )
        }
        val elements = ExtractSelector().suggestElementsToExtract(file, range)
        if (elements.isEmpty()) {
            return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                RefactoringBundle.message("selected.block.should.represent.a.set.of.statements.or.an.expression"),
                "Give the range of whole statements or of one complete expression, e.g. the lines of a loop.",
            )
        }
        val conflicts = MultiMap<PsiElement, String>()
        val options = try {
            options(elements, extraction, conflicts)
        } catch (e: ExtractException) {
            // The IDE names the places as ranges, e.g. each exit of the code.
            val places = e.problems.mapNotNull { e.file.findElementAt(it.startOffset) }.ifEmpty { elements.take(1) }
            places.forEach { conflicts.putValue(it, e.message) }
            null
        }
        return ToolResult.success(prepared(file, elements, options, conflicts))
    }

    /** The IDE's options for [elements] as [extraction] wants them; null with [conflicts] when they cannot be. */
    private fun options(
        elements: List<PsiElement>,
        extraction: MethodExtraction,
        conflicts: MultiMap<PsiElement, String>,
    ): ExtractOptions? {
        val all = ExtractMethodPipeline.findAllOptionsToExtract(elements)
        val inferred = all.firstOrNull { it.targetClass !is PsiAnonymousClass } ?: all.firstOrNull()
        if (inferred == null) {
            conflicts.putValue(elements.first(), "The IDE finds no class to add the extracted method to.")
            return null
        }
        val named = inferred.copy(
            methodName = extraction.methodName,
            visibility = extraction.visibility?.javaModifier ?: inferred.visibility,
        )
        val options = when (extraction.makeStatic) {
            // As the IDE's own "Declare static": instance fields the code uses become parameters where possible.
            true -> named.takeIf { it.isStatic }
                ?: ExtractMethodPipeline.withForcedStatic(CodeFragmentAnalyzer(elements), named)

            false -> named.takeUnless { it.isStatic }

            null -> named
        }
        if (options == null) {
            conflicts.putValue(
                elements.first(),
                if (extraction.makeStatic == true) {
                    "The extracted method cannot be static: the code uses members of ${named.targetClass.name} that " +
                        "cannot be passed as parameters."
                } else {
                    "The extracted method must be static: the code is in a static context."
                },
            )
        }
        return options
    }

    private fun prepared(
        file: PsiFile,
        elements: List<PsiElement>,
        options: ExtractOptions?,
        conflicts: MultiMap<PsiElement, String>,
    ): PreparedRefactoring {
        val method = options?.let { MethodExtractor().prepareRefactoringElements(it).method }
        if (options != null && method != null) {
            ConflictsUtil.checkMethodConflicts(options.targetClass, null, method, conflicts)
        }
        val extracted = TextRange(elements.first().textRange.startOffset, elements.last().textRange.endOffset)
        val positions = PsiDocumentManager.getInstance(file.project).getDocument(file)?.positionsOf(extracted)
        return PreparedRefactoring(
            usages = UsageInfo.EMPTY_ARRAY,
            conflicts = conflicts,
            declarationFiles = listOfNotNull(file.virtualFile),
            commandName = McpCompanionBundle.message("command.extract.method", options?.methodName.orEmpty()),
            summary = method?.let { "${signatureOf(it)}, from $positions" },
        ) {
            // Only called for an analysis without conflicts, which always has options.
            MethodExtractor().extractMethod(checkNotNull(options))
        }
    }

    /** `private static int sum(int a, int b)`: the declaration without its body, on one line. */
    private fun signatureOf(method: PsiMethod): String {
        val text = method.text
        val body = method.body?.startOffsetInParent ?: text.length
        return text.substring(0, body).replace(WHITESPACE, " ").trim()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
