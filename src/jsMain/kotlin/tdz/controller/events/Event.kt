package tdz.controller.events

import tdz.model.ColumnId
import tdz.view.CanvasColumns
import web.keyboard.KeyCode

open class Event(val type: EventType) {
}

data class ToggleColumn(val columnId: ColumnId): Event(EventType.TOGGLE_COLUMN)

data class NavColumn(val direction: KeyCode, val canvasColumns: CanvasColumns): Event(EventType.NAV_COLUMN)