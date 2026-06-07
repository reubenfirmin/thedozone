package tdz.controller.events

import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.ColumnId
import tdz.model.Direction
import tdz.model.Split
import tdz.model.card.CardListItem
import web.mouse.MouseEvent

interface Event

data class TaskToggleDone(val sourceEvent: MouseEvent, val canvasName: CanvasName, val card: Card, val segment: CardListItem): Event

data class CardMoved(val canvasName: CanvasName, val card: Card, val fromColumnId: Int, val toColumnId: Int): Event

data class ColumnEdited(val markdown: String, val canvasName: CanvasName, val columnId: Int): Event

data class RefreshColumns(val canvasName: CanvasName, val columnIds: Set<Int>): Event

data class SwitchToCanvas(val canvasName: CanvasName): Event

data class CanvasSwitched(val canvas: Canvas): Event

/** "Even": divide [columnId] into two equal panes in direction [split]. */
data class SplitColumn(val canvasName: CanvasName, val split: Split, val columnId: ColumnId): Event

/** "Distribute": add a pane to the layout containing [columnId], in direction [split]. */
data class AddColumn(val canvasName: CanvasName, val split: Split, val columnId: ColumnId): Event

/** Delete [columnId], rearranging the layout (discards that column's cards). */
data class DeleteColumn(val canvasName: CanvasName, val columnId: ColumnId): Event

/** Remove all completed checklist items from every card on the canvas. */
data class ClearDone(val canvasName: CanvasName): Event

/** Move column focus in [direction] (fired from the editor's Alt+arrow keys). */
data class NavigateColumn(val direction: Direction): Event

/** Switch [columnId] back to card mode (fired from the editor's Esc key). */
data class ExitEditor(val columnId: ColumnId): Event

/**
 * Re-render the whole current canvas. [structural] = true (default) means the canvas data/layout
 * actually changed and should be pushed to sync; false means a pure UI re-render (theme switch, or
 * restoring images after a PNG capture) that must NOT trigger a sync push.
 */
data class RefreshCanvas(val canvasName: CanvasName, val structural: Boolean = true): Event

/** Broadcasts the current sync lifecycle state to badge / overlay / modal. */
data class SyncStatusChanged(val state: tdz.sync.SyncState) : Event

/** A conflict was resolved last-write-wins; [winner] is "local" or "remote". */
data class SyncConflict(val path: String, val winner: String) : Event