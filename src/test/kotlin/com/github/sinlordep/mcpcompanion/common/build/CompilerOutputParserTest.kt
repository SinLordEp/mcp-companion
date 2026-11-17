package com.github.sinlordep.mcpcompanion.common.build

import com.github.sinlordep.mcpcompanion.common.build.CompilerOutputParser.RawProblem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Compiler lines as Gradle (javac, kotlinc) and Maven print them; samples from the step-7 end-to-end runs. */
class CompilerOutputParserTest {

    private fun parse(vararg lines: String): List<RawProblem> {
        val parser = CompilerOutputParser()
        lines.forEach(parser::accept)
        return parser.problems()
    }

    @Test
    fun `javac errors take their column from the caret line`() {
        val problems = parse(
            "> Task :app:compileJava FAILED",
            "/work/fix/app/src/main/java/app/Broken.java:5: error: cannot find symbol",
            "        return Lib.missing();",
            "                  ^",
            "  symbol:   method missing()",
            "  location: class Lib",
            "1 error",
        )
        assertEquals(
            listOf(
                RawProblem(
                    ProblemSeverity.ERROR,
                    "cannot find symbol",
                    "/work/fix/app/src/main/java/app/Broken.java",
                    5,
                    19,
                ),
            ),
            problems,
        )
    }

    @Test
    fun `a javac message without a caret line keeps no column, and warnings are warnings`() {
        val problems = parse(
            "/w/A.java:3: warning: [removal] Integer(int) in Integer has been deprecated and marked for removal",
            "Note: Some input files use unchecked or unsafe operations.",
            "/w/B.java:9: error: ';' expected",
        )
        assertEquals(listOf(ProblemSeverity.WARNING, ProblemSeverity.ERROR), problems.map { it.severity })
        assertEquals(listOf<Int?>(null, null), problems.map { it.column })
        assertEquals(
            "[removal] Integer(int) in Integer has been deprecated and marked for removal",
            problems[0].message,
        )
    }

    @Test
    fun `Maven compiler plugin lines carry line and column`() {
        val problems = parse(
            "[INFO] --- compiler:3.13.0:compile (default-compile) @ app ---",
            "[ERROR] /w/app/src/main/java/app/Broken.java:[5,19] cannot find symbol",
            "[WARNING] /w/core/src/main/java/core/Old.java:[7,24] Integer(int) in java.lang.Integer has been " +
                "deprecated",
            "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile",
        )
        assertEquals(
            listOf(
                RawProblem(ProblemSeverity.ERROR, "cannot find symbol", "/w/app/src/main/java/app/Broken.java", 5, 19),
                RawProblem(
                    ProblemSeverity.WARNING,
                    "Integer(int) in java.lang.Integer has been deprecated",
                    "/w/core/src/main/java/core/Old.java",
                    7,
                    24,
                ),
            ),
            problems,
        )
    }

    @Test
    fun `kotlinc lines with and without the file scheme`() {
        val problems = parse(
            "e: file:///w/src/main/kotlin/A.kt:12:5 Unresolved reference 'x'.",
            "w: /w/src/main/kotlin/B.kt:3:9 Variable 'y' is never used",
        )
        assertEquals(
            listOf(
                RawProblem(ProblemSeverity.ERROR, "Unresolved reference 'x'.", "/w/src/main/kotlin/A.kt", 12, 5),
                RawProblem(ProblemSeverity.WARNING, "Variable 'y' is never used", "/w/src/main/kotlin/B.kt", 3, 9),
            ),
            problems,
        )
    }

    @Test
    fun `ANSI colours, Windows paths and repeated messages`() {
        val problems = parse(
            "\u001B[31m[ERROR] C:\\w\\App.java:[1,2] bad\u001B[0m",
            "[ERROR] C:\\w\\App.java:[1,2] bad",
        )
        assertEquals(listOf(RawProblem(ProblemSeverity.ERROR, "bad", "C:\\w\\App.java", 1, 2)), problems)
    }

    @Test
    fun `lines that only look similar are not problems`() {
        assertEquals(
            emptyList<RawProblem>(),
            parse(
                "relative/A.java:3: error: not absolute",
                "e: something went wrong",
                "BUILD FAILED in 2s",
                "    ^",
            ),
        )
    }

    @Test
    fun `the build tool's own failure reasons are kept without position`() {
        val parser = CompilerOutputParser()
        listOf(
            "FAILURE: Build failed with an exception.",
            "",
            "* What went wrong:",
            "Could not resolve all files for configuration ':app:compileClasspath'.",
            "[ERROR] Failed to execute goal on project buildbad: Could not resolve dependencies for project " +
                "com.example:buildbad:jar:1.0",
            "[ERROR] Failed to execute goal on project buildbad: Could not resolve dependencies for project " +
                "com.example:buildbad:jar:1.0",
        ).forEach(parser::accept)
        assertEquals(
            listOf(
                "Could not resolve all files for configuration ':app:compileClasspath'.",
                "Failed to execute goal on project buildbad: Could not resolve dependencies for project " +
                    "com.example:buildbad:jar:1.0",
            ),
            parser.failures(),
        )
        assertEquals(emptyList<RawProblem>(), parser.problems())
    }
}
