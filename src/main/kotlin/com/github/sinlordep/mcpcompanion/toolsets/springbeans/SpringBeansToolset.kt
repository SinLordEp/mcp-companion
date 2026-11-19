package com.github.sinlordep.mcpcompanion.toolsets.springbeans

import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.springbeans.SpringBeansAction
import com.github.sinlordep.mcpcompanion.springbeans.SpringBeansArguments
import com.github.sinlordep.mcpcompanion.springbeans.spring.springBeansControlFor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.symbols.ToolParameters
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `action`. */
@Serializable
enum class SpringBeansActionInput(val action: SpringBeansAction) {
    @SerialName("list")
    LIST(SpringBeansAction.LIST),

    @SerialName("dependencies")
    DEPENDENCIES(SpringBeansAction.DEPENDENCIES),
}

/**
 * MCP adapter for `mc_spring_beans`. Registered in `mcp-companion-spring-ultimate.xml`: it needs the IDE's Spring
 * plugin with IntelliJ IDEA's paid features loaded.
 */
class SpringBeansToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_spring_beans")
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
        openWorldHint = McpToolHintValue.FALSE,
    )
    @McpDescription(description = DESCRIPTION)
    suspend fun springBeans(
        @McpDescription(SpringBeansParameterTexts.ACTION) action: SpringBeansActionInput = SpringBeansActionInput.LIST,
        @McpDescription(SpringBeansParameterTexts.MODULE) module: String? = null,
        @McpDescription(SpringBeansParameterTexts.NAME) name: String? = null,
        @McpDescription(SpringBeansParameterTexts.NAME_PATTERN) namePattern: String? = null,
        @McpDescription(SpringBeansParameterTexts.TYPE) type: String? = null,
        @McpDescription(SpringBeansParameterTexts.INCLUDE_LIBRARIES) includeLibraries: Boolean? = null,
        @McpDescription(SpringBeansParameterTexts.BEAN) bean: String? = null,
        @McpDescription(SpringBeansParameterTexts.QUALIFIED_NAME) qualifiedName: String? = null,
        @McpDescription(SpringBeansParameterTexts.PATH) path: String? = null,
        @McpDescription(SpringBeansParameterTexts.LINE) line: Int? = null,
        @McpDescription(ToolParameters.LIMIT) limit: Int? = null,
        @McpDescription(ToolParameters.OFFSET) offset: Int? = null,
    ): SpringBeansOutput {
        val project = currentCoroutineContext().project
        val arguments = SpringBeansArguments(
            module = module,
            name = name,
            namePattern = namePattern,
            type = type,
            includeLibraries = includeLibraries,
            bean = bean,
            qualifiedName = qualifiedName,
            path = path,
            line = line,
            limit = limit,
            offset = offset,
        )
        return springBeansControlFor(project).execute(action.action, arguments)
            .map(SpringBeansOutput::from)
            .toMcpOutput()
    }

    companion object {
        const val ID: String = "spring_beans"

        private const val DESCRIPTION: String = "Spring beans and their wiring from the IDE's Spring model " +
            "(IntelliJ IDEA with the Spring plugin; a paid feature). Read-only. HTTP endpoints are the official " +
            "spring_web_endpoints and related tools. status=ready, or indexing (call again later) or " +
            "no_spring_model (the module has no Spring application context the IDE knows; not an empty list). " +
            "action=list (default): the beans of module (default: the whole project) with name, aliases, type, " +
            "kind (component, bean_method, xml, other), scope, profiles, primary, module and the place of the " +
            "definition (path as read_file takes it, line, column). Filters: name (part of a name or alias, any " +
            "case) or namePattern (regex), type (fully qualified; the type and its subtypes). Only beans defined " +
            "in the project unless includeLibraries=true (hiddenLibraryBeans counts the others). Sorted by name; " +
            "limit/offset page (default 100). action=dependencies: one bean, by bean (name or alias), " +
            "qualifiedName (its class) or path and line of its definition; injections: what it receives (field, " +
            "setter, method, constructor_parameter, bean_method_parameter) with the injected bean and status " +
            "resolved, ambiguous (several candidates fit, listed), none (no bean fits; required=false when " +
            "optional) or all (collection, array or map); injectedInto: the project's beans that receive it, " +
            "each injection point with its place and status. Candidates of annotated fields and setters come from " +
            "the IDE (source=ide), those of constructor and @Bean method parameters from the beans of the " +
            "declared type (source=type), decided by Spring's rules (qualifier, primary, parameter name). " +
            "Injections written in XML (property, constructor-arg) are not read (notes say so)."
    }
}

/** Parameter descriptions of `mc_spring_beans`: prompts for the agent, so short. */
private object SpringBeansParameterTexts {
    const val ACTION = "list (default) or dependencies."
    const val MODULE = "Module name (mc_modules list). Default: every module of the project."
    const val NAME = "list: only beans whose name or an alias contains this text (any case)."
    const val NAME_PATTERN = "list: instead of name, a Java regex a bean name or alias must contain."
    const val TYPE = "list: only beans of this fully qualified type or a subtype, e.g. com.example.PaymentGateway."
    const val INCLUDE_LIBRARIES = "list: also beans defined in libraries (e.g. Spring Boot auto-configuration)."
    const val BEAN = "dependencies: the bean's name or alias."
    const val QUALIFIED_NAME = "dependencies: instead of bean, the fully qualified class of the bean."
    const val PATH = "dependencies: instead of bean, the file that defines it (with line)."
    const val LINE = "dependencies: 1-based line of the definition (the class, @Bean method or <bean> tag)."
}
