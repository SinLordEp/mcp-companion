package com.github.sinlordep.mcpcompanion.testing

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.extensions.PluginId
import kotlinx.coroutines.CoroutineScope
import java.lang.reflect.Proxy
import java.nio.file.Path

/**
 * A plugin descriptor with only what the owner check reads: id, vendor, organization and whether it is bundled.
 * Any other call fails, so a test notices when production code starts to depend on more.
 */
fun fakePluginDescriptor(
    id: String,
    vendor: String?,
    bundled: Boolean,
    organization: String? = null,
): PluginDescriptor {
    val type = PluginDescriptor::class.java
    return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
        when (method.name) {
            "getPluginId" -> PluginId.getId(id)
            "getVendor" -> vendor
            "getOrganization" -> organization
            "isBundled" -> bundled
            "getName" -> id
            "toString" -> "fake plugin $id"
            "hashCode" -> id.hashCode()
            else -> throw UnsupportedOperationException("fake descriptor: ${method.name}")
        }
    } as PluginDescriptor
}

/** A third-party plugin installed from Marketplace: neither bundled nor developed by JetBrains. */
fun thirdPartyDescriptor(): PluginDescriptor =
    fakePluginDescriptor("io.example.third-party-mcp", vendor = "Example Vendor", bundled = false)

/** A [ThirdPartyProbeToolset] whose class belongs to the plugin of [descriptor], as if that plugin shipped it. */
fun thirdPartyToolset(descriptor: PluginDescriptor = thirdPartyDescriptor()): McpToolset {
    val loader = FakePluginClassLoader(descriptor, setOf(ThirdPartyProbeToolset::class.java.name))
    return loader.load(ThirdPartyProbeToolset::class.java).getDeclaredConstructor().newInstance() as McpToolset
}

/**
 * Loads the classes named in [ownClasses] itself, the way the IDE loads a plugin's classes, so they belong to the
 * plugin of [descriptor]; everything else comes from the test class path. Only the members the platform's public
 * API reads are implemented.
 */
class FakePluginClassLoader(
    private val descriptor: PluginDescriptor,
    private val ownClasses: Set<String>,
    parent: ClassLoader = FakePluginClassLoader::class.java.classLoader,
) : ClassLoader(parent),
    PluginAwareClassLoader {

    /** A fresh copy of [type], loaded as a class of this plugin. */
    fun <T> load(type: Class<T>): Class<*> = loadClass(type.name)

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name !in ownClasses) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            val bytes = checkNotNull(parent.getResourceAsStream(name.replace('.', '/') + ".class")) { name }
                .use { it.readBytes() }
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    override fun getPluginDescriptor(): PluginDescriptor = descriptor

    override fun getPluginId(): PluginId = descriptor.pluginId

    override fun getModuleId(): String? = null

    override fun getEdtTime(): Long = 0

    override fun getBackgroundTime(): Long = 0

    override fun getLoadedClassCount(): Long = ownClasses.size.toLong()

    override fun getFiles(): Collection<Path> = emptyList()

    override fun getState(): Int = PluginAwareClassLoader.ACTIVE

    override fun tryLoadingClass(name: String, forceLoadFromSubPluginClassloader: Boolean): Class<*>? =
        if (name in ownClasses) loadClass(name) else null

    override fun loadClassInsideSelf(name: String): Class<*>? = tryLoadingClass(name, false)

    override fun getPackagePrefix(): String? = null

    override fun getPluginCoroutineScope(): CoroutineScope = throw UnsupportedOperationException("fake plugin")
}
