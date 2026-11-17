package com.github.sinlordep.mcpcompanion.build

import com.github.sinlordep.mcpcompanion.common.build.BuildResult
import com.github.sinlordep.mcpcompanion.common.build.BuildRun
import com.github.sinlordep.mcpcompanion.common.build.MessageSource
import com.github.sinlordep.mcpcompanion.common.modulegraph.Dependency
import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyScope
import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyTarget
import com.github.sinlordep.mcpcompanion.common.modulegraph.ProjectGraph
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import kotlinx.coroutines.CompletableDeferred

/** A project model and a builder whose builds end when the test says so. */
class FakeBuildEnvironment : BuildEnvironment {
    var trusted = true
    var canBuild = true
    private val modules = mutableSetOf<String>()
    private val dependencies = mutableListOf<Dependency>()
    val builder = FakeModuleBuilder()

    fun modules(vararg names: String) = apply { modules += names }

    fun dependsOn(from: String, to: String, scope: DependencyScope = DependencyScope.COMPILE) = apply {
        dependencies += Dependency(from, DependencyTarget.Module(to), scope, exported = false)
    }

    override fun isTrusted(): Boolean = trusted

    override suspend fun moduleGraph(): ProjectGraph = ProjectGraph(modules.toSet(), dependencies.toList())

    override fun builder(): ModuleBuilder? = builder.takeIf { canBuild }
}

/** Records each build's modules; a build waits for [finish] (or ends at once when [immediate] is set). */
class FakeModuleBuilder : ModuleBuilder {
    val requests = mutableListOf<List<String>>()
    var immediate: BuildRun? = BuildRun(BuildResult.SUCCEEDED, emptyList(), MessageSource.COMPILER)
    var failure: Exception? = null
    private var pending = CompletableDeferred<BuildRun>()

    override suspend fun build(modules: List<String>): BuildRun {
        requests += modules
        failure?.let { throw it }
        return immediate ?: pending.await()
    }

    fun finish(run: BuildRun) {
        pending.complete(run)
        pending = CompletableDeferred()
    }
}

fun <T> ToolResult<T>.value(): T =
    (this as? ToolResult.Success)?.value ?: throw AssertionError("expected success: $this")

fun ToolResult<*>.failure() = (this as? ToolResult.Failure)?.error ?: throw AssertionError("expected failure: $this")
