package tdz.controller.events

import tdz.view.CanvasColumns
import tdz.view.ColumnContainer
import web.keyboard.KeyCode

open class Event(val type: EventType) {
}

data class ToggleColumn(val columnContainer: ColumnContainer): Event(EventType.TOGGLE_COLUMN)

data class NavColumn(val direction: KeyCode, val canvasColumns: CanvasColumns): Event(EventType.NAV_COLUMN)