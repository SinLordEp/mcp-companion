package com.github.sinlordep.mcpcompanion.common.build.java

import com.github.sinlordep.mcpcompanion.common.build.BuildProblem
import com.github.sinlordep.mcpcompanion.common.build.CompilerOutputParser
import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.common.paths.ProjectPathRenderer
import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Key
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the console output of the executions that start while a build runs, which is where a build delegated to
 * Gradle or Maven prints its compiler messages. `processStarting` comes before the process prints anything, so no line
 * is missed. Output arrives in chunks; lines are assembled per process before they are parsed. Listeners live as long
 * as [lifetime] (the build).
 */
internal class BuildToolConsole(private val paths: ProjectPathRenderer, private val lifetime: Disposable) :
    ExecutionListener {

    /** True once any execution was seen during the build. */
    val observed = AtomicBoolean()
    private val parsers = mutableListOf<CompilerOutputParser>()

    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        observed.set(true)
        val parser = CompilerOutputParser()
        synchronized(this) { parsers += parser }
        handler.addProcessListener(LineReader(parser), lifetime)
    }

    /**
     * The compiler messages of every process; for a process without a compiler error, the build tool's own reasons
     * for its failure (without position) instead.
     */
    @Synchronized
    fun problems(): List<BuildProblem> = parsers.flatMap { parser ->
        val (problems, failures) = synchronized(parser) { parser.problems() to parser.failures() }
        val positioned = problems.map {
            BuildProblem(it.severity, it.message, paths.render(it.path), it.line, it.column)
        }
        if (positioned.any { it.severity == ProblemSeverity.ERROR }) {
            positioned
        } else {
            positioned + failures.map { BuildProblem(ProblemSeverity.ERROR, it) }
        }
    }

    /** Splits a process's output into lines and feeds them to its parser; a trailing part waits for its end. */
    private class LineReader(private val parser: CompilerOutputParser) : ProcessListener {
        private val partial = StringBuilder()

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = synchronized(parser) {
            partial.append(event.text)
            var end = partial.indexOf("\n")
            while (end >= 0) {
                parser.accept(partial.substring(0, end))
                partial.delete(0, end + 1)
                end = partial.indexOf("\n")
            }
        }

        override fun processTerminated(event: ProcessEvent) = synchronized(parser) {
            if (partial.isNotEmpty()) parser.accept(partial.toString())
            partial.setLength(0)
        }
    }
}
