package tdz.view

import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import tdz.App
import tdz.view.theme.ThemeManager
import web.html.HTMLElement
import zoned.framework.dom.Ref
import zoned.framework.dom.onClick
import zoned.framework.dom.ref
import zoned.framework.interop.addToBody
import zoned.framework.interop.css
import zoned.framework.interop.getHtmlElement
import zoned.framework.routing.Router

/**
 * A single full-screen overlay layer ("#overlay") rendered ON TOP of the live canvas
 * (#app-container) without tearing it down. Overlay routes (help, settings) render into it.
 * Closing navigates back to the current canvas URL; the (idempotent) canvas route then clears
 * this layer and leaves the board untouched.
 */
object Overlay {
    const val ID = "overlay"

    fun clear() {
        getHtmlElement(ID)?.remove()
    }

    fun close() {
        Router.navigate(App.index.resource.currentCanvasUrl())
    }

    fun show(title: String, content: FlowContent.() -> Unit) {
        clear()
        val theme = ThemeManager.current
        val scrim = Ref<HTMLElement>()
        addToBody {
            div {
                id = ID
                ref(scrim)
                css {
                    position = Position.fixed
                    top = 0.px; left = 0.px; right = 0.px; bottom = 0.px
                    zIndex = 10000
                    display = Display.flex
                    alignItems = Align.center
                    justifyContent = JustifyContent.center
                    backgroundColor = Color("rgba(0,0,0,0.45)")
                    padding = Padding(24.px)
                    boxSizing = BoxSizing.borderBox
                }
                onClick { e -> if (e.target == scrim.element) close() }

                div { // panel
                    css {
                        maxWidth = 760.px
                        width = 100.pct
                        maxHeight = 85.pct
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        backgroundColor = theme.appBg
                        color = theme.fg
                        fontFamily = theme.fontFamily
                        border = Border(1.px, BorderStyle.solid, theme.menuBorder)
                        borderRadius = 8.px
                        overflow = Overflow.hidden
                    }
                    div { // header
                        css {
                            display = Display.flex
                            alignItems = Align.center
                            justifyContent = JustifyContent.spaceBetween
                            padding = Padding(12.px, 16.px)
                            borderBottom = Border(1.px, BorderStyle.solid, theme.menuBorder)
                        }
                        div { +title }
                        button {
                            +"✕"
                            css {
                                backgroundColor = Color.transparent
                                color = theme.fg
                                borderStyle = BorderStyle.none
                                cursor = Cursor.pointer
                                fontSize = 16.px
                            }
                            onClick { close() }
                        }
                    }
                    div { // scrollable body
                        css {
                            padding = Padding(16.px)
                            overflowY = Overflow.auto
                        }
                        content()
                    }
                }
            }
        }
    }
}
