package com.github.sinlordep.mcpcompanion.settings

import com.github.sinlordep.mcpcompanion.McpCompanionBundle
import com.github.sinlordep.mcpcompanion.toolsets.ToolsetOverview
import com.github.sinlordep.mcpcompanion.yield.ToolsetState
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.Component
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * Read-only table rows: one per toolset, with name, state and description. Name and state columns are as wide as
 * their longest text; the description takes the remaining width.
 */
class ToolsetTableModel(rows: List<ToolsetOverview>) :
    ListTableModel<ToolsetOverview>(
        arrayOf(
            FittedColumn(McpCompanionBundle.message("settings.column.name"), rows) { it.displayName },
            FittedColumn(McpCompanionBundle.message("settings.column.state"), rows) { stateText(it.state) },
            DescriptionColumn(McpCompanionBundle.message("settings.column.description")),
        ),
        rows,
    ) {

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    private class FittedColumn(
        name: String,
        private val rows: List<ToolsetOverview>,
        private val text: (ToolsetOverview) -> String,
    ) : ColumnInfo<ToolsetOverview, String>(name) {

        override fun valueOf(item: ToolsetOverview): String = text(item)

        override fun getWidth(table: JTable): Int {
            val metrics = table.getFontMetrics(table.font)
            val widest = (rows.map(text) + name).maxOf { metrics.stringWidth(it) }
            return widest + JBUI.scale(CELL_PADDING)
        }
    }

    /** Long descriptions are cut by the column; the tooltip shows them in full. */
    private class DescriptionColumn(name: String) : ColumnInfo<ToolsetOverview, String>(name) {
        override fun valueOf(item: ToolsetOverview): String = item.description

        override fun getRenderer(item: ToolsetOverview): TableCellRenderer = TooltipRenderer
    }

    private object TooltipRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column).also {
            toolTipText = value as? String
        }
    }

    companion object {
        private const val CELL_PADDING = 24

        fun stateText(state: ToolsetState): String = when (state) {
            ToolsetState.Available -> McpCompanionBundle.message("settings.state.available")

            is ToolsetState.Yielded ->
                McpCompanionBundle.message("settings.state.yielded", state.officialTools.sorted().joinToString())
        }
    }
}
