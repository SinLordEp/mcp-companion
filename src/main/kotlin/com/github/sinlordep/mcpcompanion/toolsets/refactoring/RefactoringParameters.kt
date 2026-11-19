package com.github.sinlordep.mcpcompanion.toolsets.refactoring

/** Description texts shared by the refactoring tools; prompts for the AI agent, so short. */
object RefactoringParameters {
    const val ACTION: String = "preview (default) or apply."
    const val LIMIT: String = "Page size of the affected files, 1-1000. Default 100."

    /** How preview and apply work, the same in every refactoring tool. */
    const val FLOW: String = "action=preview (default) changes nothing and reports fileCount, referenceCount, the " +
        "affected files (when hasMore is true, call again with offset=nextOffset) and the conflicts the IDE " +
        "finds, with canApply. action=apply analyses again and refactors only without conflicts, as one undoable " +
        "IDE command, returning changedFiles; otherwise it fails with CONFLICT. No dialog opens."
}
