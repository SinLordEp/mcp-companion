package com.github.sinlordep.mcpcompanion.modules.ide

import com.github.sinlordep.mcpcompanion.modules.FolderKind
import com.github.sinlordep.mcpcompanion.modules.SourceFolder
import com.intellij.openapi.roots.ContentEntry
import org.jetbrains.jps.model.java.JavaResourceRootProperties
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import com.intellij.openapi.roots.SourceFolder as IdeSourceFolder

/**
 * The folders of a content root with their kind. The root types come from the platform's project model
 * (`org.jetbrains.jps.model.java`, part of every IDE): Java resource roots are resources, any other root type is a
 * source or test folder by its `isForTests` flag (Kotlin, Python and other languages' root types included).
 */
internal object ContentFolders {

    /** Folders listed per content root; real projects have a handful, generated layouts can have hundreds. */
    const val MAX_PER_ROOT: Int = 100

    fun of(entry: ContentEntry, render: (String) -> String): List<SourceFolder> {
        val sources = entry.sourceFolders.map { folder ->
            SourceFolder(render(folder.url), kindOf(folder), generated = isGenerated(folder))
        }
        val excluded = entry.excludeFolderUrls.map { SourceFolder(render(it), FolderKind.EXCLUDED) }
        return (sources + excluded).sortedWith(compareBy({ it.kind }, { it.path }))
    }

    private fun kindOf(folder: IdeSourceFolder): FolderKind = when (folder.rootType) {
        JavaResourceRootType.RESOURCE -> FolderKind.RESOURCE
        JavaResourceRootType.TEST_RESOURCE -> FolderKind.TEST_RESOURCE
        else -> if (folder.isTestSource) FolderKind.TEST else FolderKind.SOURCE
    }

    private fun isGenerated(folder: IdeSourceFolder): Boolean = when (val properties = folder.jpsElement.properties) {
        is JavaSourceRootProperties -> properties.isForGeneratedSources
        is JavaResourceRootProperties -> properties.isForGeneratedSources
        else -> false
    }
}
