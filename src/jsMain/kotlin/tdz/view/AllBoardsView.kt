package tdz.view

import kotlinx.css.*
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.span
import tdz.controller.events.Event
import tdz.controller.events.SwitchToCanvas
import tdz.model.CanvasName
import tdz.model.Model
import tdz.rootCanvas
import tdz.sync.LocalBackup
import tdz.view.sync.SyncColors
import tdz.view.theme.ThemeManager
import zoned.framework.events.EventBus
import web.events.EventType
import web.events.addEventListener
import web.html.HTMLElement
import web.html.HTMLInputElement
import zoned.framework.dom.Ref
import zoned.framework.dom.onClick
import zoned.framework.dom.onMount
import zoned.framework.dom.ref
import zoned.framework.interop.addToBody
import zoned.framework.interop.css

/**
 * A global, searchable, scrollable list of EVERY board in storage — the way to reach canvases that
 * no card links to (the only other navigation is link-cards). Opened from the Page Down switcher's
 * "All boards…" entry. Built to scale: a name filter narrows the (potentially huge) list, rows
 * scroll. Per-board delete and "Delete all empty" do a LOCAL [Model.deleteCanvas] (offline-first,
 * sync-agnostic) after a recoverable [LocalBackup] snapshot; sync propagates the deletion on its own.
 */
object AllBoards {

    private var rootEl: HTMLElement? = null

    fun hide() { rootEl?.remove(); rootEl = null }

    fun show(model: Model, eventBus: EventBus<Event>) {
        hide()
        val scrim = Ref<HTMLElement>()
        val list = Ref<HTMLElement>()
        val search = Ref<HTMLInputElement>()
        fun close() = hide()
        fun open(name: CanvasName) { hide(); eventBus.fire(SwitchToCanvas(name)) }

        // Backup first (recoverable), delete locally, then either re-home (if it was the current
        // board) or rebuild the list. Confirmation is handled INLINE per row, not via window.confirm.
        fun deleteBoard(name: CanvasName) {
            LocalBackup.snapshot()
            val wasCurrent = model.currentCanvas().name.normalized() == name.normalized()
            model.deleteCanvas(name)
            if (wasCurrent) { hide(); eventBus.fire(SwitchToCanvas(rootCanvas)) } else rebuild(model, eventBus)
        }

        fun deleteAllEmpty() {
            val empty = model.allCanvasNames().filter { model.isCanvasEmpty(it) }
            if (empty.isEmpty()) return                       // nothing to do; no dialog
            LocalBackup.snapshot()
            val current = model.currentCanvas().name.normalized()
            val deletedCurrent = empty.any { it.normalized() == current }
            empty.forEach { model.deleteCanvas(it) }          // empties: no confirm, nothing to lose
            if (deletedCurrent) { hide(); eventBus.fire(SwitchToCanvas(rootCanvas)) } else rebuild(model, eventBus)
        }

        val names = model.allCanvasNames().sortedBy { it.display().lowercase() }
        val currentName = model.currentCanvas().name.normalized()
        // Typed row refs + their lowercased search key, captured at build time so the filter never
        // re-queries the DOM (no NodeList cast).
        val rows = mutableListOf<Pair<Ref<HTMLElement>, String>>()

        addToBody {
            div {
                ref(scrim)
                css {
                    position = Position.fixed; top = 0.px; left = 0.px; right = 0.px; bottom = 0.px
                    zIndex = ZLayer.MODAL; display = Display.flex
                    alignItems = Align.center; justifyContent = JustifyContent.center
                    backgroundColor = Color("rgba(0,0,0,0.45)")
                }
                onClick { e -> if (e.target == scrim.element) close() }
                div {
                    css {
                        width = 420.px; maxWidth = 92.pct; maxHeight = 80.pct
                        display = Display.flex; flexDirection = FlexDirection.column
                        backgroundColor = ThemeManager.current.menuBg; color = ThemeManager.current.menuFg
                        border = Border(1.px, BorderStyle.solid, ThemeManager.current.menuBorder)
                        borderRadius = 8.px; overflow = Overflow.hidden
                    }
                    div {
                        css { display = Display.flex; justifyContent = JustifyContent.spaceBetween
                            alignItems = Align.center; padding = Padding(12.px, 14.px)
                            borderBottom = Border(1.px, BorderStyle.solid, ThemeManager.current.menuBorder) }
                        div { css { fontSize = 16.px }; +"All boards (${names.size})" }
                        button { +"✕"; css { backgroundColor = Color.transparent; color = ThemeManager.current.menuFg
                            borderStyle = BorderStyle.none; cursor = Cursor.pointer; fontSize = 16.px }
                            onClick { close() } }
                    }
                    div {
                        css { padding = Padding(10.px, 14.px); display = Display.flex; flexDirection = FlexDirection.column }
                        input(type = InputType.text) {
                            ref(search); id = "tdz-allboards-search"
                            attributes["placeholder"] = "Filter boards…"
                            css { padding = Padding(6.px); marginBottom = 8.px
                                backgroundColor = SyncColors.inputBg; color = ThemeManager.current.menuFg
                                border = Border(1.px, BorderStyle.solid, SyncColors.inputBorder); borderRadius = 3.px }
                        }
                        button { +"Delete all empty"; css { alignSelf = Align.flexStart; marginBottom = 8.px
                            padding = Padding(4.px, 10.px); cursor = Cursor.pointer; fontSize = 12.px
                            backgroundColor = SyncColors.buttonBg; color = SyncColors.buttonFg
                            border = Border(1.px, BorderStyle.solid, SyncColors.buttonBorder); borderRadius = 4.px }
                            onClick { deleteAllEmpty() } }
                    }
                    div {
                        ref(list)
                        css { overflowY = Overflow.auto; padding = Padding(0.px, 8.px, 8.px, 8.px) }
                        names.forEach { name ->
                            val empty = model.isCanvasEmpty(name)
                            val delRef = Ref<HTMLElement>()
                            val rowRef = Ref<HTMLElement>()
                            rows += rowRef to name.display().lowercase()
                            var armed = false
                            div("tdz-board-row") {
                                ref(rowRef)
                                css { display = Display.flex; alignItems = Align.center; justifyContent = JustifyContent.spaceBetween
                                    padding = Padding(6.px, 8.px); borderRadius = 4.px
                                    if (name.normalized() == currentName) fontWeight = FontWeight.w700 }
                                div { css { cursor = Cursor.pointer; flexGrow = 1.0; display = Display.flex; flexDirection = FlexDirection.column }
                                    div {
                                        css { display = Display.flex; alignItems = Align.center }
                                        +name.leaf().ifEmpty { name.display() }
                                        if (empty) span { css { opacity = 0.6; marginLeft = 8.px; fontSize = 11.px }; +"(empty)" }
                                    }
                                    if (!name.parentName().isRoot()) {
                                        span { css { opacity = 0.5; fontSize = 11.px; marginTop = 1.px }
                                            +"in: ${name.parentName().display()}" }
                                    }
                                    onClick { open(name) } }
                                // Empty boards delete on the first ✕ (nothing to lose); non-empty boards
                                // require a second click (✕ → "Delete?") — an inline confirm, no native dialog.
                                button {
                                    ref(delRef); +"✕"
                                    css { backgroundColor = Color.transparent; color = ThemeManager.current.menuFg
                                        borderStyle = BorderStyle.none; cursor = Cursor.pointer; opacity = 0.6; fontSize = 12.px }
                                    onClick {
                                        when {
                                            empty -> deleteBoard(name)
                                            !armed -> { armed = true; delRef.element.let { it.textContent = "Delete?"; it.style.color = "#ff5555"; it.style.opacity = "1" } }
                                            else -> deleteBoard(name)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    onMount {
                        rootEl = scrim.element
                        val input = search.element
                        input.addEventListener(EventType("input"), {
                            val q = input.value.trim().lowercase()
                            for ((rowRef, key) in rows) {
                                val match = q.isEmpty() || key.contains(q)
                                rowRef.element.style.display = if (match) "flex" else "none"
                            }
                        })
                        input.focus()
                    }
                }
            }
        }
    }

    /** Rebuild after a delete, preserving the model/bus. */
    private fun rebuild(model: Model, eventBus: EventBus<Event>) = show(model, eventBus)
}
