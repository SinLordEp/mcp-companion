package com.github.sinlordep.mcpcompanion.toolsets.refactoring

import com.github.sinlordep.mcpcompanion.common.refactoring.Visibility
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `visibility`. */
@Serializable
enum class VisibilityInput(val visibility: Visibility) {
    @SerialName("public")
    PUBLIC(Visibility.PUBLIC),

    @SerialName("protected")
    PROTECTED(Visibility.PROTECTED),

    @SerialName("package_private")
    PACKAGE_PRIVATE(Visibility.PACKAGE_PRIVATE),

    @SerialName("private")
    PRIVATE(Visibility.PRIVATE),
}
