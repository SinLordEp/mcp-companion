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

            class Thing
        """.trimIndent(),
    )

    fun addTo(fixture: CodeInsightTestFixture) {
        FILES.forEach { (path, text) -> fixture.addFileToProject(path, text) }
    }
}
