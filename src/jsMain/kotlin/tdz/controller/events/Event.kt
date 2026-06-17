package tdz.controller.events

import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.CardRef
import tdz.model.ColumnId
import tdz.model.Direction
import tdz.model.Split

interface Event

/**
 * A task row was clicked: flip the [ordinal]-th checkable item of [card] (its position in
 * [tdz.model.Card.Companion.taskItems], i.e. render order). Identity is POSITIONAL — list items
 * are data classes, so two tasks with identical text are structurally equal and carrying the
 * segment itself would make them indistinguishable (toggling one toggled its twins).
 */
data class TaskToggleDone(val canvasName: CanvasName, val card: Card, val ordinal: Int): Event

/**
 * A column's persisted markdown was replaced (editor save, properties modal). [refresh] asks the
 * Controller to re-render the column afterwards — the editor passes false so its own save never
 * rebuilds the editor mid-typing.
 */
data class ColumnEdited(val markdown: String, val canvasName: CanvasName, val columnId: ColumnId, val refresh: Boolean = false): Event

/**
 * Drag-drop outcome: [toColumnId] now holds [toCards] in this order (and [fromColumnId] holds
 * [fromCards] when the drag crossed columns). The Controller persists and emits the change events.
 * [domAlreadyOrdered] = the live DOM already matches this order (Sortable arranged it and the ids
 * were renumbered in place), so the Controller persists WITHOUT firing a [RefreshColumns] rebuild.
 */
data class CardsReordered(
    val canvasName: CanvasName,
    val toColumnId: ColumnId,
    val toCards: List<Card>,
    val fromColumnId: ColumnId,
    val fromCards: List<Card>?,
    val domAlreadyOrdered: Boolean = false,
): Event

/** Cards dropped on a canvas link: move them from [sourceCanvas] into [targetCanvas]. */
data class CardsSentToCanvas(val sourceCanvas: CanvasName, val targetCanvas: CanvasName, val cards: List<Card>): Event

/**
 * MODEL FACT: the content of [columnIds] on [canvasName] changed. Sync pushes on this — every
 * local mutation must emit it, or the change silently never reaches other devices. Distinct from
 * [RefreshColumns] (a render command): [remote] = true marks changes sync itself just applied
 * from a pull, which subscribers must not push back.
 */
data class ColumnsChanged(val canvasName: CanvasName, val columnIds: Set<ColumnId>, val remote: Boolean = false): Event

/** RENDER COMMAND: rebuild these columns' DOM from the model. */
data class RefreshColumns(val canvasName: CanvasName, val columnIds: Set<ColumnId>): Event

/**
 * RENDER COMMAND: rebuild a single card element in place from the model. The cheap sibling of
 * [RefreshColumns] for changes that touch one card and leave the column's order intact (e.g. a
 * checkbox toggle) — the rest of the column's DOM is untouched.
 */
data class RefreshCard(val canvasName: CanvasName, val ref: CardRef): Event

data class SwitchToCanvas(val canvasName: CanvasName): Event

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
/**
 * Re-render [canvasName] (when current) and — if [structural] — push its manifest to sync.
 * [remote] = true marks a canvas sync itself just pulled; subscribers must not push it back.
 */
data class RefreshCanvas(val canvasName: CanvasName, val structural: Boolean = true, val remote: Boolean = false): Event

/** Broadcasts the current sync lifecycle state to badge / overlay / modal. */
data class SyncStatusChanged(val state: tdz.sync.SyncState) : Event

/** A conflict was resolved last-write-wins; [winner] is the side that was kept. */
data class SyncConflict(val path: String, val winner: tdz.sync.Winner) : Event