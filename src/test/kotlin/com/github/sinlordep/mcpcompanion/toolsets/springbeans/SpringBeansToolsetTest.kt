package com.github.sinlordep.mcpcompanion.toolsets.springbeans

import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * `mc_spring_beans` through the real gateway: the IDE's Spring model of a small Spring Boot application, with the real
 * Spring jars as the test project's library (build.gradle.kts passes their paths).
 */
class SpringBeansToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<SpringBeansToolset>().single()

    override fun setUp() {
        super.setUp()
        tools = ToolCalls(project)
    }

    override fun tearDown() {
        try {
            tools.dispose()
            removeSpringLibrary()
        } finally {
            super.tearDown()
        }
    }

    /** The light test project is shared by the test classes: the library must not outlive the test. */
    private fun removeSpringLibrary() {
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.orderEntries.filter { it is LibraryOrderEntry && it.libraryName == LIBRARY }
                .forEach(model::removeOrderEntry)
        }
        val table = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        table.getLibraryByName(LIBRARY)?.let { library -> runWriteAction { table.removeLibrary(library) } }
    }

    private fun addSpringApplication() {
        val jars = System.getProperty("mcpCompanion.springLibrary").orEmpty().split(File.pathSeparator)
            .filter { it.endsWith(".jar") }
        assertTrue("no Spring jars passed by the build", jars.isNotEmpty())
        val roots = jars.map { path ->
            val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(path))) { path }
            checkNotNull(JarFileSystem.getInstance().getJarRootForLocalFile(file)) { path }
        }
        PsiTestUtil.addProjectLibrary(module, LIBRARY, roots, emptyList())
        SOURCES.forEach { (path, text) -> myFixture.addFileToProject(path, text) }
    }

    private fun list(
        module: String? = null,
        name: String? = null,
        namePattern: String? = null,
        type: String? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): SpringBeansOutput = tools.call(TOOL) {
        toolset.springBeans(
            module = module,
            name = name,
            namePattern = namePattern,
            type = type,
            limit = limit,
            offset = offset,
        )
    }

    private fun dependencies(
        bean: String? = null,
        qualifiedName: String? = null,
        path: String? = null,
        line: Int? = null,
    ): SpringBeansOutput = tools.call(TOOL) {
        toolset.springBeans(
            action = SpringBeansActionInput.DEPENDENCIES,
            bean = bean,
            qualifiedName = qualifiedName,
            path = path,
            line = line,
        )
    }

    private fun SpringBeansOutput.injectionTexts(): List<String> = injections.orEmpty().map { injection ->
        val injected = injection.injected.orEmpty().joinToString { it.name.orEmpty() }
        "${injection.kind.name.lowercase()} ${injection.name} ${injection.status.name.lowercase()} $injected".trim()
    }

    private fun error(block: suspend () -> SpringBeansOutput): String = tools.errorCode(TOOL, block)

    fun testWithoutSpringTheAnswerSaysSoInsteadOfAnEmptyList() {
        val output = list()
        assertEquals(ModelStatusOutput.NO_SPRING_MODEL, output.status)
        assertNull(output.beans)
        assertTrue(output.message.orEmpty(), output.message.orEmpty().contains("not an empty list"))
        val ofModule = list(module = module.name)
        assertEquals(ModelStatusOutput.NO_SPRING_MODEL, ofModule.status)
        assertEquals(module.name, ofModule.module)
        assertTrue(ofModule.message.orEmpty(), ofModule.message.orEmpty().startsWith("Module '${module.name}' has no "))
        assertEquals(ModelStatusOutput.NO_SPRING_MODEL, dependencies(bean = "anything").status)
        assertEquals("NOT_FOUND", error { toolset.springBeans(module = "no-such-module") })
    }

    fun testArgumentsAreCheckedFirst() {
        assertEquals("INVALID_ARGUMENT", error { toolset.springBeans(name = "a", namePattern = "b") })
        assertEquals("INVALID_ARGUMENT", error { toolset.springBeans(bean = "a") })
        assertEquals(
            "INVALID_ARGUMENT",
            error { toolset.springBeans(action = SpringBeansActionInput.DEPENDENCIES) },
        )
    }

    fun testTheBeansOfASpringBootApplication() {
        addSpringApplication()
        val output = list()
        assertEquals(output.message, ModelStatusOutput.READY, output.status)
        val beans = output.beans.orEmpty()
        assertEquals(
            listOf("cardGateway", "cashGateway", "clock", "notifier", "orderService", "shopApplication", "shopConfig"),
            beans.map { it.name },
        )
        assertTrue(output.hiddenLibraryBeans ?: 0 > 0)
        val clock = beans.single { it.name == "clock" }
        assertEquals(BeanKindOutput.BEAN_METHOD, clock.kind)
        assertEquals("shop/ShopConfig.java:8", "${clock.path}:${clock.line}")
        assertEquals(listOf("dev"), clock.profiles)
        val notifier = beans.single { it.name == "notifier" }
        assertEquals(BeanKindOutput.COMPONENT, notifier.kind)
        assertEquals("shop/Notifier.java:4", "${notifier.path}:${notifier.line}")
        assertEquals(
            listOf("cardGateway", "cashGateway"),
            list(type = "shop.PaymentGateway").beans.orEmpty().map { it.name },
        )
        assertEquals(listOf("cashGateway"), list(namePattern = "^cash").beans.orEmpty().map { it.name })
        val page = list(limit = 2, offset = 2)
        assertEquals(listOf("clock", "notifier"), page.beans.orEmpty().map { it.name })
        assertEquals(4, page.nextOffset)
        assertEquals("NOT_FOUND", error { toolset.springBeans(type = "shop.NoSuchType") })
    }

    fun testInjectionsBothWays() {
        addSpringApplication()
        val notifier = dependencies(bean = "notifier")
        assertEquals(notifier.message, ModelStatusOutput.READY, notifier.status)
        assertEquals(
            listOf("field gateway ambiguous", "field refunds none", "setter backup none"),
            notifier.injectionTexts(),
        )
        assertEquals(listOf(true, false, false), notifier.injections.orEmpty().map { it.required })
        assertEquals(
            listOf("cardGateway", "cashGateway"),
            notifier.injections.orEmpty().first().candidates.orEmpty().map { it.name },
        )
        val service = dependencies(qualifiedName = "shop.OrderService")
        assertEquals(
            listOf("constructor_parameter gateway resolved cardGateway", "constructor_parameter clock resolved clock"),
            service.injectionTexts(),
        )
        val clock = dependencies(path = "shop/ShopConfig.java", line = 8)
        assertEquals("clock", clock.bean?.name)
        assertEquals(listOf("orderService"), clock.injectedInto.orEmpty().map { it.bean.name })
        assertEquals(
            "NOT_FOUND",
            error { toolset.springBeans(action = SpringBeansActionInput.DEPENDENCIES, bean = "x") },
        )
    }

    companion object {
        private const val TOOL = "mc_spring_beans"
        private const val LIBRARY = "spring-test-library"

        private val SOURCES = mapOf(
            "shop/ShopApplication.java" to """
                package shop;
                @org.springframework.boot.autoconfigure.SpringBootApplication
                public class ShopApplication {}
            """.trimIndent(),
            "shop/PaymentGateway.java" to "package shop;\npublic interface PaymentGateway {}\n",
            "shop/CardGateway.java" to "package shop;\n@org.springframework.stereotype.Component\n" +
                "public class CardGateway implements PaymentGateway {}\n",
            "shop/CashGateway.java" to "package shop;\n@org.springframework.stereotype.Component\n" +
                "public class CashGateway implements PaymentGateway {}\n",
            "shop/Refunds.java" to "package shop;\npublic interface Refunds {}\n",
            "shop/Notifier.java" to """
                package shop;
                import org.springframework.beans.factory.annotation.Autowired;
                @org.springframework.stereotype.Component
                public class Notifier {
                    @Autowired private PaymentGateway gateway;
                    @Autowired(required = false) private Refunds refunds;
                    @Autowired(required = false) public void setBackup(Refunds backup) {}
                }
            """.trimIndent(),
            "shop/Clock.java" to "package shop;\npublic class Clock {}\n",
            "shop/ShopConfig.java" to """
                package shop;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.context.annotation.Profile;
                @Configuration
                public class ShopConfig {
                    @Bean @Profile("dev")
                    public Clock clock() { return new Clock(); }
                }
            """.trimIndent(),
            "shop/OrderService.java" to """
                package shop;
                import org.springframework.beans.factory.annotation.Qualifier;
                @org.springframework.stereotype.Service
                public class OrderService {
                    public OrderService(@Qualifier("cardGateway") PaymentGateway gateway, Clock clock) {}
                }
            """.trimIndent(),
        )
    }
}
