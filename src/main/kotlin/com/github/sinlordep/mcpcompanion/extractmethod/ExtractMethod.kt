package com.github.sinlordep.mcpcompanion.extractmethod

import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringAction
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringActions
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringGateway
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringOutcome
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringRequest
import com.github.sinlordep.mcpcompanion.common.refactoring.SourceRangeTargets
import com.github.sinlordep.mcpcompanion.common.refactoring.Visibility
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.source.SourceRange

/**
 * The method `mc_extract_method` creates: its [methodName], [visibility] (null: the IDE's default, private) and
 * whether it is static ([makeStatic] null: as the IDE decides, static exactly when the code is in a static context).
 * Parameters, return type and exceptions are the IDE's inference.
 */
@ConsistentCopyVisibility
data class MethodExtraction private constructor(
    val methodName: String,
    val visibility: Visibility?,
    val makeStatic: Boolean?,
) {
    companion object {
        /** Validates client input as far as it does not depend on the language. */
        fun of(methodName: String?, visibility: Visibility?, makeStatic: Boolean?): ToolResult<MethodExtraction> {
            val name = methodName?.trim().orEmpty()
            return if (name.isEmpty()) {
                ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "methodName is missing.",
                    "Pass the name of the new method, e.g. computeTotal.",
                )
            } else {
                ToolResult.success(MethodExtraction(name, visibility, makeStatic))
            }
        }
    }
}

/** Use case of `mc_extract_method`: the shared preview and apply actions with the method-extraction gateway. */
class ExtractMethod(gateway: RefactoringGateway<SourceRange, MethodExtraction>) {

    private val actions = RefactoringActions(SourceRangeTargets, gateway, verb = "extracted")

    suspend fun execute(
        action: RefactoringAction,
        request: RefactoringRequest<SourceRange, MethodExtraction>,
    ): ToolResult<RefactoringOutcome<SourceRange>> = actions.execute(action, request)
}
