package com.github.sinlordep.mcpcompanion.structuralsearch.ssr

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.structuralsearch.StructuralSearchUtil

/**
 * The languages Structural Search can read templates in (one file type per language with a Structural Search profile:
 * Java, Kotlin, XML, HTML, ... as installed), named by their language's display name ("Java", "Kotlin").
 */
internal object SearchFileTypes {

    private const val DEFAULT = "JAVA"

    fun nameOf(fileType: LanguageFileType): String = fileType.language.displayName

    /**
     * The file type [name] means (display name, language id or file type name, any case); null: Java, else the IDE's
     * default.
     */
    fun resolve(name: String?): ToolResult<LanguageFileType> {
        val available = StructuralSearchUtil.getSuitableFileTypes()
        if (name == null) {
            val default = available.firstOrNull { it.name == DEFAULT || it.language.id == DEFAULT }
                ?: StructuralSearchUtil.getDefaultFileType()
            return ToolResult.success(default)
        }
        return available.firstOrNull { it.isCalled(name) }?.let { ToolResult.success(it) } ?: ToolResult.failure(
            ErrorCode.UNSUPPORTED,
            "Structural Search cannot read templates in '$name' in this IDE.",
            "Use one of: ${available.map(::nameOf).distinct().sorted().joinToString()}.",
        )
    }

    private fun LanguageFileType.isCalled(name: String): Boolean =
        listOf(language.displayName, language.id, this.name).any { it.equals(name, ignoreCase = true) }
}
