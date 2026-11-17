package com.github.sinlordep.mcpcompanion.modules

/** Gateway boundary: the IDE's project model, read in one consistent snapshot per call. */
interface ProjectModelGateway {
    /** Every module with its content roots. */
    suspend fun modules(): List<ModuleDescription>

    /** Every module and its module dependencies; with [includeLibraries] also its library dependencies. */
    suspend fun graph(includeLibraries: Boolean): ProjectGraph
}
