package com.github.sinlordep.mcpcompanion.springbeans

import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocation

/** Beans and injection points for the platform-free tests of `mc_spring_beans`. */
object SpringBeansFakes {

    fun bean(
        name: String?,
        aliases: List<String> = emptyList(),
        origin: FileOrigin = FileOrigin.PROJECT,
        primary: Boolean = false,
        fallback: Boolean = false,
        qualifiers: List<String> = emptyList(),
    ) = SpringBean(
        id = "id-$name",
        name = name,
        aliases = aliases,
        type = "shop.${name.orEmpty().replaceFirstChar(Char::uppercase)}",
        kind = BeanKind.COMPONENT,
        scope = "singleton",
        profiles = emptyList(),
        primary = primary,
        fallback = fallback,
        qualifiers = qualifiers,
        module = "shop",
        location = SymbolLocation("src/${name ?: "anonymous"}.java", 1, 1, origin),
    )

    fun point(
        name: String,
        line: Int = 1,
        candidates: List<SpringBean>,
        qualifier: String? = null,
        required: Boolean = true,
        all: Boolean = false,
    ) = InjectionPoint(
        kind = InjectionKind.FIELD,
        member = name,
        name = name,
        type = "T",
        location = SymbolLocation("src/Notifier.java", line, 5, FileOrigin.PROJECT),
        qualifier = qualifier,
        required = required,
        all = all,
        candidates = candidates,
        source = CandidateSource.IDE,
    )
}
