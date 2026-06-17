package tdz.view

import kotlinx.css.*
import kotlinx.css.properties.*
import kotlinx.html.InputType
import kotlinx.html.TagConsumer
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
import tdz.view.theme.ThemeManager
import tdz.view.theme.themedStyleSheet
import web.cssom.ClassName
import web.dom.ElementId
import web.dom.document
import web.events.EventType
import web.events.addEventListener
import web.html.HTMLElement
import web.html.HTMLInputElement
import zoned.framework.dom.Ref
import zoned.framework.dom.onClick
import zoned.framework.dom.onKeyDown
import zoned.framework.dom.onMount
import zoned.framework.dom.ref
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import zoned.framework.interop.appendTo
import zoned.framework.interop.clear

/** Whether a row is shown for [query]: pinned rows (null key) always show; a blank query shows
 *  everything; a slash is path-mode (results hide so Enter creates the path); otherwise a
 *  case-insensitive substring match. [searchKey] is expected already-lowercased. Pure & tested. */
fun omniboxRowVisible(searchKey: String?, query: String): Boolean {
    if (searchKey == null) return true
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    if (q.contains("/")) return false
    return searchKey.contains(q)
}

/** A query with a slash is an explicit PATH (go/create), never a board filter. */
fun omniboxQueryIsPath(query: String): Boolean = query.trim().contains("/")

/** The human label for a board in the palette: the root has no leaf, so it gets the token "Home". */
fun omniboxLabel(name: CanvasName): String =
    if (name.isRoot()) "Home" else name.leaf().ifEmpty { name.display() }

/** Wrap an index by [delta] within [0, count): stepping past the end wraps to the start, and vice
 *  versa. Returns -1 when there's nothing to highlight. */
fun cycleIndex(current: Int, count: Int, delta: Int): Int =
    if (count <= 0) -1 else (((current + delta) % count) + count) % count

/**
 * The board command palette — the single navigation hub, opened by Ctrl/Cmd+L or Page Down.
 *
 * Top is an address bar: type a PATH (e.g. `/work/today`) and Enter to go there, creating the chain
 * if it doesn't exist — exactly like a browser URL bar (which the installed PWA window lacks). The
 * same input filters the list below as you type (typeahead). The list has two views swapped in
 * place within this one modal — never a second popup:
 *   • Children (default): the parent row, the current board's children, and an "All boards" row.
 *   • All boards: every board in storage, with "in: parent" context, per-board delete, and
 *     "Delete all empty"; a "‹ Boards" row returns.
 * Arrow keys move the highlight across rows; Enter activates it (or navigates to a typed path);
 * Page Up jumps to the parent; Esc / click-away closes.
 */
object Omnibox {
    private const val ID = "tdz-omnibox"

    // Component-private CSS classes — each appears in BOTH the markup builders and an ensureStyles()
    // selector, so a literal would silently desync on rename. (Classes used in only one place —
    // tdz-omni-child / tdz-omni-allboards / tdz-omni-board — stay inline.)
    private const val BOX_CLASS = "tdz-omnibox-box"
    private const val LABEL_CLASS = "tdz-omnibox-label"
    private const val BODY_CLASS = "tdz-omni-body"
    private const val SECTION_CLASS = "tdz-omni-section"
    private const val ALLHEADER_CLASS = "tdz-omni-allheader"
    private const val EMPTYBTN_CLASS = "tdz-omni-emptybtn"
    private const val ROW_CLASS = "tdz-omni-row"
    private const val ROW_LABEL_CLASS = "tdz-omni-row-label"
    private const val ROW_HINT_CLASS = "tdz-omni-row-hint"
    private const val BOARD_MAIN_CLASS = "tdz-omni-board-main"
    private const val BOARD_LINE_CLASS = "tdz-omni-board-line"
    private const val CURRENT_CLASS = "tdz-omni-current"
    private const val EMPTY_CLASS = "tdz-omni-empty"
    private const val SUB_CLASS = "tdz-omni-sub"
    private const val DEL_CLASS = "tdz-omni-del"
    private const val ACTIVE_CLASS = "tdz-omni-active"

    // The armed "Delete?" / danger state for a non-empty board's delete button.
    private const val ARMED_DELETE_COLOR = "#ff5555"

    private enum class Mode { CHILDREN, ALL }

    fun hide() { document.getElementById(ElementId(ID))?.remove() }

    /** One selectable row. [searchKey] is the lowercased filter key (board rows) or null for a pinned
     *  row (parent / all-boards / back), which never filters out. */
    private class Row(val ref: Ref<HTMLElement>, val searchKey: String?, val activate: () -> Unit)

    fun show(model: Model, eventBus: EventBus<Event>) {
        if (document.getElementById(ElementId(ID)) != null) return
        ensureStyles()

        val current = model.currentCanvas().name
        val field = Ref<HTMLInputElement>()
        val scrim = Ref<HTMLElement>()
        val body = Ref<HTMLElement>()

        var mode = Mode.CHILDREN
        var rows = mutableListOf<Row>()
        var visible: List<Row> = emptyList()
        var highlight = -1

        fun go(name: CanvasName) { hide(); eventBus.fire(SwitchToCanvas(name)) }
        fun applyHighlight() {
            visible.forEachIndexed { i, r -> r.ref.element.classList.toggle(ClassName(ACTIVE_CLASS), i == highlight) }
        }
        fun refilter() {
            val q = field.element.value
            visible = rows.filter { row ->
                val show = omniboxRowVisible(row.searchKey, q)
                row.ref.element.style.display = if (show) "flex" else "none"
                show
            }
            // Land the highlight on the first result (a board) by default, else the first visible row.
            highlight = visible.indexOfFirst { it.searchKey != null }
                .let { if (it >= 0) it else if (visible.isNotEmpty()) 0 else -1 }
            applyHighlight()
        }
        fun move(delta: Int) {
            if (visible.isEmpty()) return
            highlight = cycleIndex(if (highlight < 0) 0 else highlight, visible.size, delta)
            applyHighlight()
        }
        fun activateEnter() {
            val raw = field.element.value.trim()
            // A slash means "this is a path" — go/create it, regardless of any highlighted board.
            if (omniboxQueryIsPath(raw)) { if (raw.isNotEmpty()) go(CanvasName(raw)); return }
            val hit = visible.getOrNull(highlight)
            when {
                hit != null -> hit.activate()             // board / parent / all-boards / back
                raw.isNotEmpty() -> go(CanvasName(raw))   // typed a non-matching name => create it
            }
        }

        // Forward declaration so the view builders (which switch/refresh the view) can call it.
        lateinit var renderBody: () -> Unit

        // Delete locally, then either re-home (if the current board went) or rebuild the all-boards
        // view. Sync propagates the deletion on its own.
        fun deleteBoard(name: CanvasName) {
            val wasCurrent = current.normalized() == name.normalized()
            model.deleteCanvas(name)
            if (wasCurrent) go(rootCanvas) else { renderBody(); field.element.focus() }
        }
        fun deleteAllEmpty() {
            val empty = model.allCanvasNames().filter { model.isCanvasEmpty(it) }
            if (empty.isEmpty()) return                       // nothing to do; no dialog
            val deletedCurrent = empty.any { it.normalized() == current.normalized() }
            empty.forEach { model.deleteCanvas(it) }          // empties: nothing to lose, no confirm
            if (deletedCurrent) go(rootCanvas) else { renderBody(); field.element.focus() }
        }

        fun TagConsumer<HTMLElement>.pinnedRow(label: String, hint: String?, cls: String, onActivate: () -> Unit) {
            val r = Ref<HTMLElement>()
            rows += Row(r, null, onActivate)
            div("$ROW_CLASS $cls") {
                ref(r)
                span(ROW_LABEL_CLASS) { +label }
                if (hint != null) span(ROW_HINT_CLASS) { +hint }
                onClick { onActivate() }
            }
        }

        fun TagConsumer<HTMLElement>.childrenView() {
            if (!current.isRoot()) {
                val parent = current.parentName()
                pinnedRow("↑ ${omniboxLabel(parent)}", "Page Up", "") { go(parent) }
            }
            val children = childBoardNames(model, current)
            if (children.isNotEmpty()) {
                div(SECTION_CLASS) { +"Child boards" }
                children.forEach { child ->
                    val r = Ref<HTMLElement>()
                    rows += Row(r, child.leaf().lowercase()) { go(child) }
                    div("$ROW_CLASS tdz-omni-child") {
                        ref(r)
                        span(ROW_LABEL_CLASS) { +child.leaf() }
                        onClick { go(child) }
                    }
                }
            }
            if (model.allCanvasNames().size > 1) {
                pinnedRow("▤ All boards…", "→", "tdz-omni-allboards") { mode = Mode.ALL; renderBody(); field.element.focus() }
            }
        }

        fun TagConsumer<HTMLElement>.allBoardsView() {
            pinnedRow("‹ Boards", null, "") { mode = Mode.CHILDREN; renderBody(); field.element.focus() }
            val names = model.allCanvasNames().sortedBy { it.display().lowercase() }
            div(ALLHEADER_CLASS) {
                span(SECTION_CLASS) { +"All boards (${names.size})" }
                button(classes = EMPTYBTN_CLASS) {
                    +"Delete all empty"
                    onClick { deleteAllEmpty() }
                }
            }
            val currentNorm = current.normalized()
            names.forEach { name ->
                val empty = model.isCanvasEmpty(name)
                val r = Ref<HTMLElement>()
                val delRef = Ref<HTMLElement>()
                var armed = false
                rows += Row(r, name.display().lowercase().ifEmpty { "home" }) { go(name) }
                div("$ROW_CLASS tdz-omni-board" + if (name.normalized() == currentNorm) " $CURRENT_CLASS" else "") {
                    ref(r)
                    div(BOARD_MAIN_CLASS) {
                        div(BOARD_LINE_CLASS) {
                            span(ROW_LABEL_CLASS) { +omniboxLabel(name) }
                            if (empty) span(EMPTY_CLASS) { +"(empty)" }
                        }
                        if (!name.isRoot() && !name.parentName().isRoot()) {
                            span(SUB_CLASS) { +"in: ${name.parentName().display()}" }
                        }
                        onClick { go(name) }
                    }
                    // Empty boards delete on the first ✕ (nothing to lose); non-empty boards require a
                    // second click (✕ → "Delete?") — an inline confirm, no native dialog.
                    button(classes = DEL_CLASS) {
                        ref(delRef)
                        +"✕"
                        onClick {
                            when {
                                empty -> deleteBoard(name)
                                !armed -> { armed = true; delRef.element.let { it.textContent = "Delete?"; it.style.color = ARMED_DELETE_COLOR; it.style.opacity = "1" } }
                                else -> deleteBoard(name)
                            }
                        }
                    }
                }
            }
        }

        renderBody = {
            body.element.clear()
            rows = mutableListOf()
            body.element.appendTo().apply {
                when (mode) {
                    Mode.CHILDREN -> childrenView()
                    Mode.ALL -> allBoardsView()
                }
            }
            refilter()
        }

        addToBody {
            div {
                id = ID
                ref(scrim)
                onClick { e -> if (e.target === scrim.element) hide() }
                div(BOX_CLASS) {
                    div(LABEL_CLASS) { +"Go to board" }
                    input(type = InputType.text) {
                        ref(field)
                        attributes["placeholder"] = "Type a path like /work/today, or filter boards below"
                        attributes["autocomplete"] = "off"
                        attributes["spellcheck"] = "false"
                        // Keys are handled on the input so the field keeps focus (typeahead intact);
                        // stopPropagation keeps the document-level board shortcuts from also firing.
                        onKeyDown { ev ->
                            when (ev.key) {
                                "ArrowDown", "PageDown" -> { ev.preventDefault(); ev.stopPropagation(); move(1) }
                                "ArrowUp" -> { ev.preventDefault(); ev.stopPropagation(); move(-1) }
                                // Let the caret move, but don't let the document's column-nav handler fire underneath.
                                "ArrowLeft", "ArrowRight" -> ev.stopPropagation()
                                "PageUp" -> {
                                    ev.preventDefault(); ev.stopPropagation()
                                    if (!current.isRoot()) go(current.parentName())
                                }
                                "Enter" -> { ev.preventDefault(); ev.stopPropagation(); activateEnter() }
                                "Escape" -> { ev.preventDefault(); ev.stopPropagation(); hide() }
                            }
                        }
                    }
                    div(BODY_CLASS) { ref(body) }
                }
                onMount {
                    field.element.addEventListener(EventType("input"), { refilter() })
                    renderBody()
                    field.element.focus()
                }
            }
        }
    }

    private fun ensureStyles() {
        val t = ThemeManager.current
        themedStyleSheet("tdz-omnibox-styles") {
            rule("#$ID") {
                position = Position.fixed
                top = 0.px; left = 0.px; right = 0.px; bottom = 0.px
                zIndex = ZLayer.MODAL
                display = Display.flex
                justifyContent = JustifyContent.center
                alignItems = Align.flexStart
                backgroundColor = t.scrimBg
                put("padding-top", "12vh")
                fontFamily = t.fontFamily
            }
            rule("#$ID .$BOX_CLASS") {
                width = 520.px
                maxWidth = 92.pct
                maxHeight = 70.pct
                display = Display.flex
                flexDirection = FlexDirection.column
                backgroundColor = t.menuBg
                color = t.menuFg
                border = Border(1.px, BorderStyle.solid, t.menuBorder)
                borderRadius = t.dialogRadius
                padding = Padding(16.px)
                boxSizing = BoxSizing.borderBox
            }
            rule("#$ID .$LABEL_CLASS") {
                fontSize = 11.px
                textTransform = TextTransform.uppercase
                letterSpacing = 0.06.em
                opacity = 0.6
                marginBottom = 8.px
            }
            rule("#$ID input") {
                width = 100.pct
                boxSizing = BoxSizing.borderBox
                padding = Padding(10.px, 12.px)
                fontSize = 15.px
                fontFamily = t.fontFamily
                // appBg is the theme's base surface (white on light themes, dark on dark) so the field
                // reads with menuFg on both — scrimBg was a dark overlay that became gray-on-gray in
                // light themes.
                backgroundColor = t.appBg
                color = t.menuFg
                border = Border(1.px, BorderStyle.solid, t.menuBorder)
                borderRadius = 6.px
                outline = Outline.none
            }
            // The swappable list area scrolls; the box/input above stay fixed.
            rule("#$ID .$BODY_CLASS") {
                overflowY = Overflow.auto
                marginTop = 4.px
            }
            rule("#$ID .$SECTION_CLASS") {
                fontSize = 10.px
                textTransform = TextTransform.uppercase
                letterSpacing = 0.06.em
                opacity = 0.5
                margin = Margin(12.px, 0.px, 4.px, 0.px)
            }
            rule("#$ID .$ALLHEADER_CLASS") {
                display = Display.flex
                alignItems = Align.center
                justifyContent = JustifyContent.spaceBetween
            }
            rule("#$ID .$EMPTYBTN_CLASS") {
                padding = Padding(4.px, 10.px)
                cursor = Cursor.pointer
                fontSize = 11.px
                backgroundColor = Color.transparent
                color = t.menuFg
                border = Border(1.px, BorderStyle.solid, t.menuBorder)
                borderRadius = 4.px
            }
            rule("#$ID .$ROW_CLASS") {
                display = Display.flex
                alignItems = Align.center
                justifyContent = JustifyContent.spaceBetween
                padding = Padding(7.px, 10.px)
                borderRadius = 6.px
                cursor = Cursor.pointer
            }
            rule("#$ID .$BOARD_MAIN_CLASS") {
                display = Display.flex
                flexDirection = FlexDirection.column
                flexGrow = 1.0
            }
            rule("#$ID .$BOARD_LINE_CLASS") {
                display = Display.flex
                alignItems = Align.center
            }
            rule("#$ID .$CURRENT_CLASS .$ROW_LABEL_CLASS") { fontWeight = FontWeight.w700 }
            rule("#$ID .$EMPTY_CLASS") { opacity = 0.6; marginLeft = 8.px; fontSize = 11.px }
            rule("#$ID .$SUB_CLASS") { opacity = 0.5; fontSize = 11.px; marginTop = 1.px }
            rule("#$ID .$DEL_CLASS") {
                backgroundColor = Color.transparent
                color = t.menuFg
                borderStyle = BorderStyle.none
                cursor = Cursor.pointer
                opacity = 0.6
                fontSize = 12.px
            }
            rule("#$ID .$ROW_CLASS .$ROW_HINT_CLASS") { fontSize = 11.px; opacity = 0.5 }
            rule("#$ID .$ROW_CLASS.$ACTIVE_CLASS") { backgroundColor = t.menuHover }
        }
    }
}
