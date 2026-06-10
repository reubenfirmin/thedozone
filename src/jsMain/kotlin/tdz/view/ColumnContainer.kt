package tdz.view

import kotlinx.browser.document
import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.DragDrop
import tdz.controller.events.EventBus
import tdz.model.Canvas
import tdz.model.Card
import tdz.view.Mode.*
import web.events.Event
import web.events.EventType
import web.events.addEventListener
import web.html.HTMLElement
import web.mouse.MouseEvent
import tdz.view.theme.ThemeManager
import kotlinx.css.properties.BoxShadow
import kotlinx.css.properties.BoxShadowInset
import zoned.framework.interop.css
import zoned.framework.interop.encodeURIComponent
import zoned.framework.interop.getHtmlElement

/**
 * A column that contains cards.
 */
class ColumnContainer(
    headingColor: Color,
    val canvas: Canvas,
    val mode: Mode,
    val cards: List<Card>,
    val boundary: Boundary,
    private val columnId: Int,
    val eventBus: EventBus) {

    private val darkHeader = Border(2.px, BorderStyle.solid, color = Color.black)
    private val highlightHeader = Border(2.px, BorderStyle.solid, color = headingColor)

    private var focused = false
    private var editorColumn: EditorColumn? = null

    fun FlowContent.render() {
        val t = ThemeManager.current
        val columnCss: CssBuilder.() -> Unit = {
            height = 100.pct
            border = Border(t.borderWidth, BorderStyle.valueOf(t.borderStyle), t.columnBorder)
            backgroundColor = t.panelBg
            borderRadius = t.columnRadius
            t.columnShadow.forEach { boxShadow += it }
            if (t.panelBackdropBlur.isNotEmpty()) backdropFilter = "blur(${t.panelBackdropBlur})"
        }
        when (mode) {
            CARD -> div("w-full h-full mx-4 overflow-y-auto") {
                id = DomIds.column(columnId)
                css(columnCss)
                paint()
            }
            EDITOR -> div("w-full h-full mx-4") {
                id = DomIds.column(columnId)
                css { columnCss(); overflow = Overflow.hidden }
                paint()
            }
        }
    }

    fun setupDragDrop(dragDrop: DragDrop) {
        dragDrop.setupColumn(DomIds.column(columnId), canvas.name)
    }

    /** Focus this column's editor, if it is in editor mode. No-op otherwise. */
    fun focusEditor() {
        editorColumn?.focus()
    }

    fun FlowContent.paint() {
        val canvasName = canvas.name
        console.log("ColumnContainer.paint() called for column $columnId, mode=$mode, cards.size=${cards.size}")

        when (mode) {
            CARD -> {
                console.log("Rendering CARD mode for column $columnId with ${cards.size} cards")
                with (CardRenderer) {
                    render(canvasName, cards, eventBus)
                }
            }
            EDITOR -> {
                console.log("Rendering EDITOR mode for column $columnId")
                val editor = EditorColumn(canvas, cards, columnId, eventBus)
                editorColumn = editor
                with(editor) {
                    render()
                }
            }
        }
    }

    fun beforeRemoval() {
        editorColumn?.destroy()
        // The per-column accent <style> (see CanvasColumns.setColumnHeaderAccent) is re-injected on
        // (re)mount, so dropping it here keeps deleted columns from orphaning rules in <head>.
        document.getElementById(accentStyleId(columnId))?.remove()
    }

    fun setFocused(isFocused: Boolean) {
        focused = isFocused
        val t = ThemeManager.current
        val element = getHtmlElement(DomIds.column(columnId)) ?: return
        // A per-column accent color (Poodle Rocks: set via the column-properties color) overrides
        // the theme focus color, so the hover highlight matches the column's own color. Absent on
        // other themes/columns → the theme default.
        val focusColor = element.getAttribute("data-focus-color") ?: t.columnFocusBorder.toString()
        if (t.focusTopStripe) {
            // Bold top accent stripe via an inset shadow (no layout shift), layered over the sheet's
            // own elevation; the whole frame is left alone. element.style.boxShadow is a string DOM
            // sink, so the typed BoxShadow layers are rendered to CSS at the edge.
            val stripe: BoxShadow = BoxShadowInset(Color(focusColor), 0.px, 3.px, 0.px, 0.px)
            val sheet = t.columnShadow
            element.style.boxShadow = when {
                isFocused -> (listOf(stripe) + sheet).joinToString(", ")
                sheet.isNotEmpty() -> sheet.joinToString(", ")
                else -> "none"
            }
        } else {
            // Only the color changes on focus; width/style come from the theme border set at render.
            element.style.borderColor =
                if (isFocused) focusColor
                else t.columnBorder.toString()
        }
        // Optional decorative focus sprite (Nyan, in Rainbow Gaps); no-op when the theme has none.
        ColumnFocusSprite.update(DomIds.column(columnId), t.columnFocusSprite, isFocused)
    }

    /** Debug aid: tile this column's id faintly across its background, to identify columns. */
    fun showDebugBackground() {
        val element = getHtmlElement(DomIds.column(columnId)) ?: return
        // The SVG data-URI needs a literal color string; take it from the typed theme token.
        val fill = ThemeManager.current.watermark
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='110' height='64'>" +
            "<text x='8' y='40' font-family='monospace' font-size='30' fill='$fill'>$columnId</text></svg>"
        element.style.backgroundImage = "url(\"data:image/svg+xml,${encodeURIComponent(svg)}\")"
        element.style.backgroundRepeat = "repeat"
    }

    /**
     * Focus follows the mouse: entering the column focuses it. `focusin` also covers the editor
     * (Ace focuses its textarea and `focusin` bubbles). Typed listeners, no `asDynamic`.
     */
    fun setupFocusHandler(onFocus: () -> Unit) {
        val element = getHtmlElement(DomIds.column(columnId)) ?: return
        val handler: (Event) -> Unit = { onFocus() }
        element.addEventListener(EventType("mouseenter"), handler)
        element.addEventListener(EventType("focusin"), handler)
    }

    /** Double-click the column to act on it (used to open a card column in the editor). */
    fun setupDoubleClickHandler(onDoubleClick: () -> Unit) {
        val element = getHtmlElement(DomIds.column(columnId)) ?: return
        element.addEventListener(EventType("dblclick"), { onDoubleClick() })
    }

    companion object {
        /** Id of the injected <style> carrying a column's header-accent rule. */
        fun accentStyleId(columnId: Int) = "tdz-col-accent-$columnId"
    }
 }