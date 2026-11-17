package com.github.sinlordep.mcpcompanion.guide

/**
 * The optional guide for AI agents: cross-tool advice that tool descriptions cannot carry, for users to paste into
 * AGENTS.md or CLAUDE.md. The bundled Markdown resource is the one source; the repository's Claude skill
 * (`skills/mcp-companion/SKILL.md`) carries the same body, checked by a test. English in every IDE language, because
 * its reader is the agent.
 *
 * @param resource classpath path of the guide; replaceable in tests.
 */
class AgentGuide(private val resource: String = RESOURCE) {

    /** The guide as Markdown, read on each call (it is read only when a user asks for it). */
    fun text(): String {
        val stream = checkNotNull(AgentGuide::class.java.getResourceAsStream(resource)) {
            "The agent guide $resource is missing from the plugin"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    companion object {
        const val RESOURCE: String = "/agent/mcp-companion-guide.md"
    }
}
