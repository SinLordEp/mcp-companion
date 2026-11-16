package com.github.sinlordep.mcpcompanion.modules.java

import com.github.sinlordep.mcpcompanion.modules.ide.ModuleFactsProvider
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.module.LanguageLevelUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.roots.libraries.Library

/**
 * Java facts of modules and libraries; registered in `mcp-companion-java.xml`. The language level is reported for
 * Java modules only; Maven coordinates come from the library's own properties (Maven and Gradle libraries, and JPS
 * repository libraries), never parsed from its name.
 */
class JvmModuleFacts : ModuleFactsProvider {

    override fun languageLevel(module: Module): String? {
        if (ModuleType.get(module).id != JAVA_MODULE) return null
        val level = LanguageLevelUtil.getEffectiveLanguageLevel(module)
        val feature = level.toJavaVersion().feature.toString()
        return if (level.isPreview) "$feature-preview" else feature
    }

    override fun coordinates(library: Library): String? = JavaLibraryUtil.getMavenCoordinates(library)?.let {
        listOf(it.groupId, it.artifactId, it.version).joinToString(":")
    }

    private companion object {
        const val JAVA_MODULE = "JAVA_MODULE"
    }
}
