package com.github.sinlordep.mcpcompanion.yield

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.extensions.PluginDescriptor

/**
 * Finds the owner of a toolset class from its class loader: every plugin class is loaded by a
 * [PluginAwareClassLoader], which knows its plugin's descriptor; a class without one belongs to the IDE itself.
 * `PluginManager.getPluginByClass` does the same but is internal API. The owner of a loaded class never changes, so
 * it is memoised per class; a [ClassValue] does not keep unloaded plugins' classes alive.
 */
class PluginClassLoaderOwnerGateway : ToolsetOwnerGateway {

    private val cache = object : ClassValue<ToolsetOwner>() {
        override fun computeValue(type: Class<*>): ToolsetOwner =
            ownerOf((type.classLoader as? PluginAwareClassLoader)?.pluginDescriptor)
    }

    override fun ownerOf(type: Class<*>): ToolsetOwner = cache.get(type)

    internal companion object {
        /** The owner a [descriptor] stands for; `null` is the IDE itself. */
        fun ownerOf(descriptor: PluginDescriptor?): ToolsetOwner = if (descriptor == null) {
            ToolsetOwner.Platform
        } else {
            ToolsetOwner.Plugin(
                bundled = descriptor.isBundled,
                developedByJetBrains = PluginManagerCore.isDevelopedByJetBrains(descriptor),
            )
        }
    }
}
