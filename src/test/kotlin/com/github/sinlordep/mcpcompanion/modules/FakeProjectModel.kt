package com.github.sinlordep.mcpcompanion.modules

import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** A project model built in the test: modules by name and their dependencies. */
class FakeProjectModel(
    val modules: MutableList<ModuleDescription> = mutableListOf(),
    val dependencies: MutableList<Dependency> = mutableListOf(),
) : ProjectModelGateway {

    var graphCalls = 0
        private set

    override suspend fun modules(): List<ModuleDescription> = modules.toList()

    override suspend fun graph(includeLibraries: Boolean): ProjectGraph {
        graphCalls++
        val names = modules.map { it.name }.toSet()
        val deps = dependencies.filter { includeLibraries || it.target is DependencyTarget.Module }.map { dependency ->
            val target = dependency.target
            if (target is DependencyTarget.Module && target.name !in names) {
                dependency.copy(target = target.copy(missing = true))
            } else {
                dependency
            }
        }
        return ProjectGraph(names, deps)
    }

    fun module(name: String, vararg roots: ContentRoot): FakeProjectModel = apply {
        modules += ModuleDescription(name, "JAVA_MODULE", null, null, null, roots.toList())
    }

    fun add(vararg names: String): FakeProjectModel = apply { names.forEach { module(it) } }

    fun dependsOn(
        from: String,
        to: String,
        scope: DependencyScope = DependencyScope.COMPILE,
        exported: Boolean = false,
    ): FakeProjectModel = apply {
        dependencies += Dependency(from, DependencyTarget.Module(to), scope, exported)
    }

    fun usesLibrary(from: String, library: String, coordinates: String? = null): FakeProjectModel = apply {
        dependencies += Dependency(from, DependencyTarget.Library(library, coordinates), DependencyScope.COMPILE, false)
    }
}

fun <T> ToolResult<T>.value(): T =
    (this as? ToolResult.Success)?.value ?: throw AssertionError("expected success: $this")

fun ToolResult<*>.code() = (this as? ToolResult.Failure)?.error?.code ?: throw AssertionError("expected failure: $this")

fun ToolResult<*>.message() = (this as ToolResult.Failure).error.message
