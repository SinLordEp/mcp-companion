package com.github.sinlordep.mcpcompanion.testing

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/** Builds library jars for tests: compiled classes, and optionally a sources jar, from Java source text. */
object LibraryJars {

    /** Compiles [sources] (path inside the jar to text) and writes `<name>.jar` (classes) into [directory]. */
    fun classesJar(directory: File, name: String, sources: Map<String, String>): File {
        val sourceDir = File(directory, "$name-src").apply { mkdirs() }
        val classDir = File(directory, "$name-classes").apply { mkdirs() }
        val files = sources.map { (path, text) ->
            File(sourceDir, path).apply {
                parentFile.mkdirs()
                writeText(text)
            }
        }
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "tests need a JDK with javac" }
        val exit = compiler.run(null, null, null, "-d", classDir.path, *files.map { it.path }.toTypedArray())
        check(exit == 0) { "javac failed for $name" }
        return jar(File(directory, "$name.jar"), classDir)
    }

    /** Writes `<name>-sources.jar` with [sources] into [directory]. */
    fun sourcesJar(directory: File, name: String, sources: Map<String, String>): File {
        val root = File(directory, "$name-sources").apply { mkdirs() }
        sources.forEach { (path, text) ->
            File(root, path).apply {
                parentFile.mkdirs()
                writeText(text)
            }
        }
        return jar(File(directory, "$name-sources.jar"), root)
    }

    private fun jar(target: File, root: File): File {
        JarOutputStream(target.outputStream()).use { out ->
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                out.putNextEntry(JarEntry(file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return target
    }
}
