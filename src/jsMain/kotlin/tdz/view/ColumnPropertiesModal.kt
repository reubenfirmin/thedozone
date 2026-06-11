package tdz.view

import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.input
import tdz.controller.events.ColumnEdited
import tdz.controller.events.Event as TdzEvent
import zoned.framework.events.EventBus
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnStyle
import tdz.model.Model
import tdz.view.modalButton
import tdz.view.theme.ThemeManager
import web.dom.ElementId
import web.dom.document
import web.events.Event
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
 * A small body-level modal for per-column visual properties: a wallpaper image URL and a background
 * color. Values are persisted into the column's own markdown (so they sync with column content) and
 * the column is re-rendered to reflect them. Follows the SyncModal rendering model.
 */
class ColumnPropertiesModal(
    private val model: Model,
    private val eventBus: EventBus<TdzEvent>,
) {
    private var rootEl: HTMLElement? = null
    private var canvasName: CanvasName = CanvasName("")
    private var columnId: ColumnId = ColumnId(0)

    fun show(canvasName: CanvasName, columnId: ColumnId) {
        this.canvasName = canvasName
        this.columnId = columnId
        render()
    }

    fun hide() { rootEl?.remove(); rootEl = null }

    private fun render() {
        hide()
        val style = model.getColumnStyle(canvasName, columnId)
        val colorValue = style.color?.takeIf { it.startsWith("#") } ?: "#ffffff"
        val r = Ref<HTMLElement>()
        addToBody {
            div {   // scrim
                ref(r)
                css {
                    position = Position.fixed
                    top = 0.px; left = 0.px; right = 0.px; bottom = 0.px
                    zIndex = ZLayer.MODAL
                    display = Display.flex
                    alignItems = Align.center
                    justifyContent = JustifyContent.center
                    backgroundColor = ThemeManager.current.scrimBg
                }
                onClick { e -> if (e.target == r.element) hide() }
                div {   // dialog
                    css {
                        minWidth = 360.px
                        padding = Padding(20.px)
                        backgroundColor = ThemeManager.current.menuBg
                        color = ThemeManager.current.menuFg
                        border = Border(1.px, BorderStyle.solid, ThemeManager.current.menuBorder)
                        borderRadius = 6.px
                    }
                    div { css { fontSize = 18.px; marginBottom = 14.px }; +"Column properties" }

                    field("Wallpaper (image URL)", WALLPAPER_ID, style.wallpaper ?: "")

                    // An <input type=color> can never be empty, so "use the theme/palette color" can't
                    // live in the picker itself — it needs its own switch. Unchecked = no override (the
                    // theme/Metro palette shows through); apply() only persists a color when it's checked.
                    div {
                        css { marginBottom = 4.px; display = Display.flex; flexDirection = FlexDirection.row; alignItems = Align.center }
                        input(type = InputType.checkBox) {
                            id = COLOR_ENABLED_ID
                            if (style.color != null) attributes["checked"] = "checked"
                            css { marginRight = 8.px; cursor = Cursor.pointer }
                        }
                        div { css { fontSize = 12.px; marginRight = 10.px }; +"Custom color" }
                        input(type = InputType.color) {
                            id = COLOR_ID
                            attributes["value"] = colorValue
                            css { width = 48.px; height = 28.px; padding = Padding(0.px); border = Border(1.px, BorderStyle.solid, ThemeManager.current.menuBorder) }
                        }
                    }
                    div {
                        css { fontSize = 11.px; marginBottom = 12.px; opacity = 0.7 }
                        +"Unchecked uses the theme / Metro palette color."
                    }

                    div {
                        css { display = Display.flex; flexDirection = FlexDirection.row; marginTop = 6.px }
                        button("Apply", primary = true) { apply() }
                        button("Clear") { clear() }
                        button("Cancel") { hide() }
                    }

                    onMount {
                        rootEl = r.element
                        // Convenience: actually choosing a color in the swatch implies "use a custom
                        // color", so tick the enabling checkbox automatically — otherwise apply()
                        // (which gates on that checkbox) silently drops the picked color. Wired on
                        // change/input — a real selection — not click, so opening then cancelling the
                        // OS picker never bakes in the default color.
                        (document.getElementById(ElementId(COLOR_ID)) as? HTMLInputElement)?.let { picker ->
                            val enable: (Event) -> Unit = {
                                (document.getElementById(ElementId(COLOR_ENABLED_ID)) as? HTMLInputElement)?.checked = true
                            }
                            picker.addEventListener(EventType("input"), enable)
                            picker.addEventListener(EventType("change"), enable)
                        }
                    }
                }
            }
        }
    }

    private fun apply() {
        val wallpaper = inputVal(WALLPAPER_ID).trim().ifBlank { null }
        // No color unless the switch is on — so leaving the picker at its default never bakes in white.
        val color = if (isChecked(COLOR_ENABLED_ID)) inputVal(COLOR_ID).ifBlank { null } else null
        save(ColumnStyle(wallpaper, color))
    }

    private fun clear() = save(ColumnStyle(null, null))

    private fun save(style: ColumnStyle) {
        model.setColumnStyle(canvasName, columnId, style)
        // ColumnEdited persists the rewritten file and emits the change events (Controller);
        // refresh=true re-renders the column so the new wallpaper/color is painted.
        val raw = model.getCanvasColumnRaw(canvasName, columnId) ?: ""
        eventBus.fire(ColumnEdited(raw, canvasName, columnId, refresh = true))
        hide()
    }

    private fun inputVal(id: String): String =
        (document.getElementById(ElementId(id)) as? HTMLInputElement)?.value ?: ""

    private fun isChecked(id: String): Boolean =
        (document.getElementById(ElementId(id)) as? HTMLInputElement)?.checked ?: false

    private fun FlowContent.field(labelText: String, inputId: String, value: String) {
        div {
            css { marginBottom = 12.px; display = Display.flex; flexDirection = FlexDirection.column }
            div { css { fontSize = 12.px; marginBottom = 3.px }; +labelText }
            input(type = InputType.text) {
                id = inputId
                attributes["value"] = value
                attributes["placeholder"] = "https://…"
                css {
                    padding = Padding(6.px)
                    backgroundColor = ThemeManager.current.menuBg
                    color = ThemeManager.current.menuFg
                    border = Border(1.px, BorderStyle.solid, ThemeManager.current.menuBorder)
                    borderRadius = 3.px
                }
            }
        }
    }

    private fun FlowContent.button(text: String, primary: Boolean = false, onPress: () -> Unit) =
        modalButton(text, primary, onPress = onPress)

    companion object {
        private const val WALLPAPER_ID = "tdz-col-wallpaper"
        private const val COLOR_ID = "tdz-col-color"
        private const val COLOR_ENABLED_ID = "tdz-col-color-on"
    }
}
