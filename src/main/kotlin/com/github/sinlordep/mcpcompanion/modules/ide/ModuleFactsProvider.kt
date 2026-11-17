package com.github.sinlordep.mcpcompanion.modules.ide

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.libraries.Library

/**
 * Facts about modules and libraries that only optional plugins know: the owning build system (external-system
 * module, `mcp-companion-external-system.xml`), the language level and Maven coordinates (Java plugin,
 * `mcp-companion-java.xml`). Every answer may be null; the first non-null answer of the registered providers wins.
 * Called inside a read action.
 */
interface ModuleFactsProvider {

    /** `gradle`, `maven` or another build system id, lower case. */
    fun buildSystem(module: Module): String? = null

    /** The effective language level, e.g. `21`. */
    fun languageLevel(module: Module): String? = null

    /** `group:artifact:version`. */
    fun coordinates(library: Library): String? = null

    companion object {
        val EP: ExtensionPointName<ModuleFactsProvider> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.moduleFacts")
    }
}
