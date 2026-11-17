package com.github.sinlordep.mcpcompanion.testing

import com.intellij.testFramework.fixtures.CodeInsightTestFixture

/** A position in a fixture file, 1-based as tools take it. */
data class Position(val path: String, val line: Int, val column: Int)

/**
 * Small Java code base shared by the navigation and safe-delete platform tests. The default light test project has no
 * JDK, so `java.lang.Object` and other JDK types do not resolve; the sources avoid relying on them.
 */
object NavigationFixture {

    val FILES: Map<String, String> = mapOf(
        "shapes/Shape.java" to """
            package shapes;
            public interface Shape {
                double area();
            }
        """.trimIndent(),
        "shapes/Circle.java" to """
            package shapes;
            public class Circle implements Shape {
                private double radius;
                public Circle(double r) { radius = r; }
                public double area() { return 3 * radius * radius; }
                public void grow() { radius++; }
            }
        """.trimIndent(),
        "shapes/Square.java" to """
            package shapes;
            public class Square implements Shape {
                double side;
                public double area() { return side * side; }
                public double scale(double f) { return side * f; }
                public double scale(double f, double g) { return side * f * g; }
            }
        """.trimIndent(),
        "shapes/RoundedSquare.java" to """
            package shapes;
            public class RoundedSquare extends Square {
                public double area() { return super.area() - 1; }
            }
        """.trimIndent(),
        "shapes/Unused.java" to """
            package shapes;
            public class Unused {
                public void nothing() {}
            }
        """.trimIndent(),
        "app/Main.java" to """
            package app;
            import shapes.Circle;
            import shapes.Shape;
            public class Main {
                public static double total() {
                    Shape s = new Circle(2);
                    Shape t = new Shape() { public double area() { return 1; } };
                    return s.area() + t.area();
                }
            }
        """.trimIndent(),
    )

    fun addTo(fixture: CodeInsightTestFixture) {
        FILES.forEach { (path, text) -> fixture.addFileToProject(path, text) }
    }

    /** Where the [occurrence]-th (0-based) [snippet] starts in [path], plus [shift] columns. */
    fun at(path: String, snippet: String, occurrence: Int = 0, shift: Int = 0): Position {
        val text = FILES.getValue(path)
        var offset = -1
        repeat(occurrence + 1) { offset = text.indexOf(snippet, offset + 1) }
        check(offset >= 0) { "'$snippet' #$occurrence not in $path" }
        val before = text.substring(0, offset)
        val line = before.count { it == '\n' } + 1
        val column = offset - (before.lastIndexOf('\n') + 1) + 1 + shift
        return Position(path, line, column)
    }
}
