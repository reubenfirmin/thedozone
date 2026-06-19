package tdz.view

import kotlinx.css.*
import kotlinx.css.properties.*
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.div
import kotlinx.html.input
import tdz.controller.events.ColumnEdited
import tdz.controller.events.Event as TdzEvent
import zoned.framework.events.EventBus
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnStyle
import tdz.model.Model
import tdz.view.theme.ThemeManager
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
 * color. The colour control is a real inline picker ([Picker], the `vanilla-picker` library) — SV
 * area + hue slider + hex editor — plus a "Theme default" toggle for "no override". Values persist
 * into the column's own markdown (so they sync with content) and the column re-renders.
 */
class ColumnPropertiesModal(
    private val model: Model,
    private val eventBus: EventBus<TdzEvent>,
) {
    private var rootEl: HTMLElement? = null
    private var picker: Picker? = null
    private var wallpaperInput: Ref<HTMLInputElement>? = null
    private var colorValueInput: Ref<HTMLInputElement>? = null
    private var canvasName: CanvasName = CanvasName("")
    private var columnId: ColumnId = ColumnId.ROOT

    fun show(canvasName: CanvasName, columnId: ColumnId) {
        this.canvasName = canvasName
        this.columnId = columnId
        render()
    }

    fun hide() {
        picker?.destroy(); picker = null
        rootEl?.remove(); rootEl = null
        wallpaperInput = null; colorValueInput = null
    }

    private fun render() {
        hide()
        val style = model.getColumnStyle(canvasName, columnId)
        val r = Ref<HTMLElement>()
        val wallpaperRef = Ref<HTMLInputElement>().also { wallpaperInput = it }
        val colorValueRef = Ref<HTMLInputElement>().also { colorValueInput = it }
        val swatchRef = Ref<HTMLElement>()
        val themeBtnRef = Ref<HTMLElement>()
        addToBody {
            div {   // scrim
                ref(r)
                css {
                    position = Position.fixed
                    top = 0.px; left = 0.px; right = 0.px; bottom = 0.px
                    zIndex = ZLayer.MODAL
                    display = Display.flex; alignItems = Align.center; justifyContent = JustifyContent.center
                    backgroundColor = ThemeManager.current.scrimBg
                }
                onClick { e -> if (e.target == r.element) hide() }
                div {   // dialog
                    css {
                        minWidth = 320.px; maxWidth = 380.px
                        padding = Padding(24.px)
                        backgroundColor = ThemeManager.current.menuBg
                        color = ThemeManager.current.menuFg
                        border = Border(1.px, BorderStyle.solid, ThemeManager.current.menuBorder)
                        borderRadius = 8.px
                        boxShadow = BoxShadows().apply { this += BoxShadow(ThemeManager.current.shadow, 0.px, 12.px, 36.px) }
                    }
                    div {
                        css { fontSize = 20.px; fontWeight = FontWeight.w600; marginBottom = 18.px
                            color = ThemeManager.current.menuTitle }
                        +"Column properties"
                    }

                    field("Wallpaper image URL", wallpaperRef, style.wallpaper ?: "")

                    sectionLabel("Background color")
                    input(type = InputType.hidden) { ref(colorValueRef); attributes["value"] = style.color ?: "" }
                    div {
                        css { display = Display.flex; alignItems = Align.center; marginBottom = 4.px }
                        // Clickable swatch — opens the colour-picker POPUP (not inline).
                        div {
                            ref(swatchRef)
                            attributes["title"] = "Click to pick a color"
                            css {
                                width = 56.px; height = 34.px; marginRight = 12.px
                                borderRadius = 6.px; cursor = Cursor.pointer
                                border = Border(1.px, BorderStyle.solid, ThemeManager.current.menuBorder)
                            }
                        }
                        // "No override" toggle — the theme / Metro palette colour shows through.
                        div {
                            ref(themeBtnRef)
                            css {
                                padding = Padding(vertical = 7.px, horizontal = 12.px)
                                fontSize = 12.px; borderRadius = 6.px; cursor = Cursor.pointer; userSelect = UserSelect.none
                                border = Border(2.px, BorderStyle.solid, ThemeManager.current.menuBorder)
                            }
                            +"Theme default"
                        }
                    }

                    div {
                        css {
                            display = Display.flex; flexDirection = FlexDirection.row; flexWrap = FlexWrap.wrap
                            marginTop = 18.px; paddingTop = 18.px
                            borderTop = Border(1.px, BorderStyle.solid, ThemeManager.current.menuBorder)
                        }
                        button("OK", primary = true) { persist(); hide() }     // save + close
                        button("Apply") { persist() }                          // save + stay open
                        button("Cancel") { hide() }
                    }

                    onMount {
                        rootEl = r.element
                        val swatch = swatchRef.element
                        val hidden = colorValueRef.element
                        val themeBtn = themeBtnRef.element

                        val accent = ThemeManager.current.link.value
                        val faint = ThemeManager.current.menuBorder.value
                        val neutral = ThemeManager.current.appBg.value
                        fun reflect() {
                            val custom = hidden.value.isNotEmpty()
                            // The swatch shows the chosen colour, or a neutral fill for theme default;
                            // the toggle is highlighted while theme default is in force.
                            swatch.style.setProperty("background", if (custom) hidden.value else neutral)
                            themeBtn.style.setProperty("border-color", if (custom) faint else accent)
                            themeBtn.style.setProperty("color", if (custom) "" else accent)
                        }

                        // Popup picker: clicking the swatch opens it (vanilla-picker attaches to the parent).
                        picker = Picker(pickerOptions {
                            parent = swatch
                            popup = "bottom"
                            color = style.color ?: "#3cb44b"
                            alpha = false
                            editor = true
                        }).also { p ->
                            p.onChange = { color ->
                                hidden.value = color.hex.substring(0, 7)   // drop alpha -> #rrggbb
                                reflect()
                            }
                        }
                        themeBtn.addEventListener(EventType("click"), {
                            hidden.value = ""                        // no override
                            reflect()
                        })
                        reflect()
                    }
                }
            }
        }
    }

    private fun persist() {
        val wallpaper = (wallpaperInput?.element?.value ?: "").trim().ifBlank { null }
        // The hidden value holds the chosen colour; "" = Theme default (no override).
        val color = (colorValueInput?.element?.value ?: "").ifBlank { null }
        model.setColumnStyle(canvasName, columnId, ColumnStyle(wallpaper, color))
        // ColumnEdited persists the rewritten file and emits the change events (Controller);
        // refresh=true re-renders the column so the new wallpaper/color is painted.
        val raw = model.getCanvasColumnRaw(canvasName, columnId) ?: ""
        eventBus.fire(ColumnEdited(raw, canvasName, columnId, refresh = true))
    }

    private fun FlowContent.sectionLabel(text: String) {
        div {
            css {
                fontSize = 11.px; fontWeight = FontWeight.w600; textTransform = TextTransform.uppercase
                letterSpacing = 0.5.px; opacity = 0.6; marginBottom = 8.px
            }
            +text
        }
    }

    private fun FlowContent.field(labelText: String, inputRef: Ref<HTMLInputElement>, value: String) {
        div {
            css { marginBottom = 16.px; display = Display.flex; flexDirection = FlexDirection.column }
            div { css { fontSize = 11.px; fontWeight = FontWeight.w600; textTransform = TextTransform.uppercase
                letterSpacing = 0.5.px; opacity = 0.6; marginBottom = 8.px }; +labelText }
            input(type = InputType.text) {
                ref(inputRef)
                attributes["value"] = value
                attributes["placeholder"] = "https://…"
                css {
                    width = 100.pct; boxSizing = BoxSizing.borderBox
                    padding = Padding(vertical = 9.px, horizontal = 10.px)
                    backgroundColor = ThemeManager.current.appBg
                    color = ThemeManager.current.menuFg
                    border = Border(1.px, BorderStyle.solid, ThemeManager.current.menuBorder)
                    borderRadius = 4.px
                }
            }
        }
    }

    private fun FlowContent.button(text: String, primary: Boolean = false, onPress: () -> Unit) =
        modalButton(text, primary, onPress = onPress)
}
