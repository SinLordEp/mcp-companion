package com.github.sinlordep.mcpcompanion.testing

import com.intellij.testFramework.fixtures.CodeInsightTestFixture

/**
 * Small Java and Kotlin code base of the refactoring platform tests (`mc_move`, `mc_change_signature`). As in
 * [NavigationFixture], the light test project has no JDK, so the sources avoid JDK types other than primitives and
 * `String` literals.
 */
object RefactoringFixture {

    val FILES: Map<String, String> = mapOf(
        "model/Item.java" to """
            package model;
            public class Item {
                public int price(int qty, String unit) { return qty; }
                public static class Tag {}
            }
        """.trimIndent(),
        "model/Base.java" to """
            package model;
            public class Base {
                public int size(int a) { return a; }
            }
        """.trimIndent(),
        "model/Sub.java" to """
            package model;
            public class Sub extends Base {
                public int size(int a) { return a + 1; }
            }
        """.trimIndent(),
        "model/Secret.java" to """
            package model;
            class Secret {
                static int code() { return 1; }
            }
        """.trimIndent(),
        "model/Uses.java" to """
            package model;
            public class Uses {
                int x() { return Secret.code(); }
            }
        """.trimIndent(),
        "store/Item.java" to """
            package store;
            public class Item {}
        """.trimIndent(),
        "app/Shop.java" to """
            package app;
            import model.Item;
            import model.Base;
            public class Shop {
                int total(Item i) { return i.price(2, "kg") + i.price(3, "g"); }
                int sized(Base b) { return b.size(4); }
            }
        """.trimIndent(),
        "kt/Client.kt" to """
            package kt

            import model.Item

            fun buy(i: Item) = i.price(1, "kg")

            class Thing {
                fun size(a: Int): Int {
                    val b = a + 1
                    return b * 2
                }
            }
        """.trimIndent(),
        // mc_pull_up
        "pull/Animal.java" to """
            package pull;
            public class Animal {
                protected int legs;
            }
        """.trimIndent(),
        "pull/Pet.java" to """
            package pull;
            public interface Pet {
            }
        """.trimIndent(),
        "pull/Dog.java" to """
            package pull;
            public class Dog extends Animal implements Pet {
                public static final int MAX = 3;
                private int age;
                public int bark() { return 1; }
                public int older() { return age + 1; }
                public int bark(int times) { return times; }
                public static final int MIN = 1;
            }
        """.trimIndent(),
        "pull/Cat.java" to """
            package pull;
            public class Cat extends Animal {
            }
        """.trimIndent(),
        "app/Kennel.java" to """
            package app;
            import pull.Dog;
            public class Kennel {
                int walk(Dog d) { return d.bark() + Dog.MAX + Dog.MIN; }
            }
        """.trimIndent(),
        "kt/Walker.kt" to """
            package kt

            import pull.Dog

            fun walk(d: Dog) = d.bark() + Dog.MAX
        """.trimIndent(),
        // mc_extract_method
        "ext/Calc.java" to """
            package ext;
            public class Calc {
                int total(int a, int b) {
                    int sum = a + b;
                    int doubled = sum * 2;
                    return doubled + 1;
                }
                int exits(int x) {
                    for (int i = 0; i < x; i++) {
                        if (i == 3) return i;
                        if (i == 5) break;
                    }
                    return -1;
                }
                static int twice(int v) {
                    int w = v * 2;
                    return w;
                }
                int helper(int v) { return v; }
            }
        """.trimIndent(),
    )

    fun addTo(fixture: CodeInsightTestFixture) {
        FILES.forEach { (path, text) -> fixture.addFileToProject(path, text) }
    }
}
