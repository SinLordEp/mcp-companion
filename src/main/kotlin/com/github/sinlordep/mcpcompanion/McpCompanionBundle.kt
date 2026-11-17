package com.github.sinlordep.mcpcompanion

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.McpCompanionBundle"

/** User-visible strings of the plugin. Strings shown to MCP clients (tool descriptions) are not UI and stay in code. */
internal object McpCompanionBundle : DynamicBundle(McpCompanionBundle::class.java, BUNDLE) {

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
