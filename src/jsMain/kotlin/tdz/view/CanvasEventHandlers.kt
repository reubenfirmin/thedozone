package tdz.view

import tdz.model.*
import org.w3c.dom.events.MouseEvent
import tdz.controller.events.EventBus
import tdz.controller.events.NavColumn
import tdz.controller.events.ToggleColumn
import web.keyboard.KeyCode
import web.keyboard.KeyCode.Companion.ArrowDown
import web.keyboard.KeyCode.Companion.ArrowLeft
import web.keyboard.KeyCode.Companion.ArrowRight
import web.keyboard.KeyCode.Companion.ArrowUp
import web.keyboard.KeyCode.Companion.KeyH
import web.keyboard.KeyCode.Companion.KeyJ
import web.keyboard.KeyCode.Companion.KeyK
import web.keyboard.KeyCode.Companion.KeyL
import web.uievents.KeyboardEvent

class CanvasEventHandlers(
    val eventBus: EventBus) {

    fun handleEvent(event: KeyboardEvent, canvas: Canvas, canvasColumns: CanvasColumns) {
        var handled = true
        // TODO column mode
        if ((event.ctrlKey && event.code == KeyCode.Quote) || (event.code == KeyCode.KeyI /* && columnModel.mode() == CARD */)) { // ctrl ' or i
            eventBus.fire(ToggleColumn(canvasColumns.focusedColumn()!!))
        } else if (!event.ctrlKey && event.code in setOf(KeyCode.ArrowUp, KeyCode.ArrowDown, KeyCode.ArrowLeft, KeyCode.ArrowRight)) {
            eventBus.fire(NavColumn(event.code, canvasColumns))
//            if (event.altKey) {
//                columnModel.moveSelected(direction)
//            }
        } else if (event.code in setOf(KeyH, KeyJ, KeyK, KeyL)) {
            eventBus.fire(NavColumn(
                when(event.code) {
                    KeyH -> ArrowLeft
                    KeyJ -> ArrowUp
                    KeyK -> ArrowDown
                    KeyL -> ArrowRight
                }, canvasColumns))
        }
        //else if (event.ctrlKey && event.keyCode == 186) { // ctrl ;
//            eventBus.fire(ClearDone(canvas.name))
//        } else if (event.ctrlKey && event.altKey && (event.keyCode == 38 || event.keyCode == 40)) { // ctrl left/right
//            if (event.shiftKey) {
//                eventBus.fire(AddColumn(canvas.name, Split.HORIZONTAL, columns.focused()))
//            } else {
//                eventBus.fire(SplitColumn(canvas.name, Split.HORIZONTAL, columns.focused()))
//            }
//        } else if (event.ctrlKey && event.altKey && (event.keyCode == 37 || event.keyCode == 39)) { // ctrl up/down
//            if (event.shiftKey) {
//                eventBus.fire(AddColumn(canvas.name, Split.VERTICAL, columns.focused()))
//            } else {
//                eventBus.fire(SplitColumn(canvas.name, Split.VERTICAL, columns.focused()))
//            }
//        } else if (event.ctrlKey && event.altKey && event.keyCode == 191) { // ctrl alt /
//            eventBus.fire(DeleteColumn(canvas.name, columns.focused()))
//        } else if (event.keyCode == 27) { // esc
//            menu.hide()
//            columnModel.toggle(CARD)
//            eventBus.fire(HideYoTings())
//        } else if (event.keyCode == 33) {// pgup
//            if (canvas.parent != null) {
//                eventBus.fire(SwitchToCanvas(canvas.parent))
//            }
//        } else if (event.keyCode == 34) {// pgdn
//            val switcher = CanvasSwitcher({ cardsStore }, eventBus, theme) {
//                remove(it)
//                focusCanvas()
//            }
//            if (switcher.links.isNotEmpty()) {
//                add(switcher)
//                switcher.showContextMenu(event, window.innerWidth / 2 - 120, window.innerHeight / 2 - 120)
//            }
//        } else if (event.keyCode == 112) { // f1
//            columnModel.toggle(CARD)
//            eventBus.fire(ShowHelp(Help.KEYREF))
//        } else if (event.keyCode == 113) { // f2
//            columnModel.toggle(CARD)
//            eventBus.fire(ShowHelp(Help.MARKDOWN))
    //    }
        else {
            console.log("Unhandled: ${event.code}")
            handled = false
        }
        if (handled) {
            event.preventDefault()
            event.stopPropagation()
        }
    }

    fun handleEvent(event: MouseEvent) {
//
//        mousemove = { event ->
//            columnModel.focus(eventToColumnId(event))
//        }
//
//        dblclick = { _ ->
//            // toggle; if card mode, then focus
//            eventBus.fire(ToggleColumn(canvas.name, columns.focused()))
//        }

    }
}