package tdz.view

import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.TagConsumer
import kotlinx.html.button
import kotlinx.html.div
import tdz.canvasPages
import tdz.view.theme.ThemeManager
import web.html.HTMLElement
import zoned.framework.dom.onClick
import zoned.framework.interop.clear
import zoned.framework.interop.css
import zoned.framework.routing.Router

/**
 * The full-screen overlay layer: a PERMANENT zone ([Zones.OVERLAY]) in the shell, sitting over the
 * live canvas without ever tearing it down. Overlay routes (help topics) are fragments that swap
 * [overlayPanel] content into it; empty = invisible (see the `:empty` rule in Index.ensureShell).
 * Closing navigates back to the current canvas URL; the (idempotent) canvas route then clears this
 * zone and leaves the board untouched.
 */
object Overlay {

    fun clear() {
        Zones.OVERLAY.resolve()?.clear()
    }

    fun close() {
        Router.navigate(canvasPages.resource.currentCanvasUrl())
    }
}

/** The overlay chrome (panel, title bar, ✕) — rendered INTO the overlay zone by overlay routes. */
fun TagConsumer<HTMLElement>.overlayPanel(title: String, content: FlowContent.() -> Unit) {
    val theme = ThemeManager.current
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
                attributes["aria-label"] = "Close"
                +"✕"
                css {
                    backgroundColor = Color.transparent
                    color = theme.fg
                    borderStyle = BorderStyle.none
                    cursor = Cursor.pointer
                    fontSize = 16.px
                }
                onClick { Overlay.close() }
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
