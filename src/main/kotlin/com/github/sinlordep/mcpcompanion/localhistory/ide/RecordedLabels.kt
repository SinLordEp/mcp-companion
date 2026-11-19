package com.github.sinlordep.mcpcompanion.localhistory.ide

import com.github.sinlordep.mcpcompanion.localhistory.HistoryLabel
import com.github.sinlordep.mcpcompanion.localhistory.LocalHistoryControl
import com.intellij.history.Label
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * The labels `mc_local_history` put in a project, kept in the project's workspace file (not shared) so that they
 * survive a restart, as the Local History does. Needed because the IDE's public API puts labels and reverts to them
 * by id but cannot list them; labels the IDE dropped are filtered out when read ([IdeLocalHistoryGateway.labels]).
 * At most [LocalHistoryControl.MAX_LABELS], the oldest are forgotten first.
 */
@Service(Service.Level.PROJECT)
@State(name = "McpCompanionLocalHistoryLabels", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class RecordedLabels : SimplePersistentStateComponent<RecordedLabels.LabelsState>(LabelsState()) {

    class LabelsState : BaseState() {
        /** `id`, tab, `timestamp`, tab, `name`: one label per entry, oldest first. */
        var labels by list<String>()
    }

    @Synchronized
    fun all(): List<HistoryLabel> = state.labels.mapNotNull(::parse).asReversed()

    /** The IDE's label objects put in this session: only they can read a file's content at the label. */
    private val live = ConcurrentHashMap<String, Label>()

    /** The IDE's label object for [id], when it was put in this IDE session. */
    fun live(id: String): Label? = live[id]

    @Synchronized
    fun add(label: HistoryLabel, ideLabel: Label) {
        live[label.id] = ideLabel
        val kept = (state.labels + format(label)).takeLast(LocalHistoryControl.MAX_LABELS)
        state.labels = kept.toMutableList()
    }

    @Synchronized
    fun retain(valid: Set<String>) {
        val kept = state.labels.filter { entry -> parse(entry)?.id in valid }
        if (kept.size != state.labels.size) {
            state.labels = kept.toMutableList()
        }
    }

    private fun format(label: HistoryLabel): String =
        listOf(label.id, label.timestamp.toString(), label.name.replace(SEPARATOR, " ")).joinToString(SEPARATOR)

    private fun parse(entry: String): HistoryLabel? {
        val parts = entry.split(SEPARATOR, limit = 3)
        val timestamp = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return HistoryLabel(parts[0], parts.getOrElse(2) { "" }, timestamp)
    }

    companion object {
        private const val SEPARATOR = "\t"

        fun getInstance(project: Project): RecordedLabels = project.service()
    }
}
