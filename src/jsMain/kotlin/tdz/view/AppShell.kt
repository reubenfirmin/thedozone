package tdz.view

import kotlinx.css.*
import kotlinx.html.div
import tdz.controller.events.Event
import web.dom.document
import zoned.framework.events.EventBus
import tdz.model.Model
import tdz.view.menu.attachContextMenu
import tdz.view.theme.ThemeManager
import tdz.view.theme.themedStyleSheet
import zoned.framework.interop.addToBody
import zoned.framework.interop.css
import zoned.framework.interop.important
import zoned.framework.dom.onClick
import zoned.framework.routing.Zone.Companion.zone

/**
 * Builds and maintains the stable app shell — the swap targets routes/refreshes render into. The
 * shell elements (WCO titlebar, the [Zones.CANVAS] and [Zones.OVERLAY] containers) are built once
 * and stay put for the life of the app; only the zone CONTENTS swap. Keeping this out of [tdz.Index]
 * leaves Index a thin render coordinator. The shell chrome (titlebar styling [ensureTitleBarStyles]
 * and the overlay scrim CSS) lives here in the view layer where it belongs.
 */
class AppShell(
    private val model: Model,
    private val eventBus: EventBus<Event>,
    private val liveColumns: () -> CanvasColumns?,
) {

    /**
     * Build the stable shell once. Routes and refreshes swap zone CONTENTS ([Zones.CANVAS],
     * [Zones.OVERLAY]); the shell elements themselves (and every body-level sibling layer) stay put.
     * Idempotent — a no-op once the canvas zone exists.
     */
    fun ensure() {
        if (Zones.CANVAS.resolve() != null) return
        addToBody {
            // Window Controls Overlay titlebar (installed PWA on desktop Chromium). Sized by the
            // titlebar-area-* env vars, which are 0 where WCO is inactive (browser tab / mobile /
            // unsupported), so it self-collapses to nothing there. Transparent — the body's themed
            // background/gradient shows straight through, so the strip always matches the theme;
            // draggable so it moves the OS window. Styles (themed text) in ensureTitleBarStyles().
            div("tdz-titlebar") { +"The Do Zone" }
            div {
                zone(Zones.CANVAS)
                // Resolve menu sections against the LIVE columns at right-click time, so the menu
                // never closes over a stale CanvasColumns after a canvas switch or refresh.
                attachContextMenu { event ->
                    liveColumns()?.let { canvasMenu(it, model, eventBus)(event) }
                        ?: emptyList()
                }
            }
            // The overlay layer is the SCRIM itself: a permanent sibling the help fragments swap
            // panels into. Empty = invisible (the :empty rule below; !important beats inline flex).
            div {
                zone(Zones.OVERLAY)
                css {
                    position = Position.fixed
                    top = 0.px; left = 0.px; right = 0.px; bottom = 0.px
                    zIndex = ZLayer.OVERLAY
                    display = Display.flex
                    alignItems = Align.center
                    justifyContent = JustifyContent.center
                    backgroundColor = ThemeManager.current.scrimBg
                    padding = Padding(24.px)
                    boxSizing = BoxSizing.borderBox
                }
                onClick { e ->
                    // Only a click on the scrim itself closes; panel clicks bubble with an inner target.
                    if ((e.target as? web.dom.Element)?.id?.toString() == Zones.OVERLAY.elementId) {
                        Overlay.close()
                    }
                }
            }
        }
        themedStyleSheet("tdz-overlay-zone") {
            // !important: the zone's inline display:flex must lose when the zone is empty.
            rule("#${Zones.OVERLAY.elementId}:empty") { important("display", "none") }
        }
    }

    /** Themed CSS for the WCO titlebar strip. Re-ensured each render so it recolors on theme switch
     *  (themedStyleSheet no-ops unless the theme changed). Sized via the titlebar-area-* env vars
     *  (0 ⇒ invisible when WCO is inactive); transparent so the body's themed gradient shows through. */
    fun ensureTitleBarStyles() {
        val t = ThemeManager.current
        themedStyleSheet("tdz-titlebar-styles") {
            rule(".tdz-titlebar") {
                position = Position.fixed
                put("left", "env(titlebar-area-x, 0px)")
                put("top", "env(titlebar-area-y, 0px)")
                put("width", "env(titlebar-area-width, 0px)")   // 0 ⇒ collapsed/invisible without WCO
                put("height", "env(titlebar-area-height, 0px)")
                zIndex = ZLayer.TITLEBAR
                display = Display.flex
                alignItems = Align.center
                paddingLeft = 12.px
                overflow = Overflow.hidden
                whiteSpace = WhiteSpace.nowrap
                color = t.menuFg
                fontFamily = t.fontFamily
                fontSize = 12.px
                fontWeight = FontWeight.w600
                letterSpacing = 0.04.em
                put("-webkit-app-region", "drag")   // the strip drags the OS window
                put("user-select", "none")
            }
        }
    }

    /** Re-apply the themed body chrome (background gradient/dither, fg color, font). Shell chrome
     *  lives here, not in [tdz.Index]; re-applied on every render so it recolors on a theme switch. */
    fun applyBodyTheme() {
        val theme = ThemeManager.current
        document.body.css {
            minHeight = 100.vh
            backgroundColor = theme.appBg
            // gradient / DOS dither / rainbow — the full background-image lives on the theme
            backgroundImage = Image(theme.appBgImage)
            color = theme.fg
            fontFamily = theme.fontFamily
            fontSize = theme.baseFontSize
        }
    }

    /** Re-apply the canvas zone's themed chrome (size, WCO-titlebar inset, edge padding). The shell
     *  is permanent, so this theme-dependent chrome is re-applied on every render. */
    fun applyCanvasZoneChrome() {
        Zones.CANVAS.resolve()?.css {
            width = 100.vw
            // Reserve the WCO titlebar strip so board content starts below it. env() is 0px when
            // not an installed PWA with an overlay, so this is a no-op in a browser tab / mobile.
            put("margin-top", "env(titlebar-area-height, 0px)")
            put("height", "calc(100vh - env(titlebar-area-height, 0px))")
            overflow = Overflow.hidden
            boxSizing = BoxSizing.borderBox
            // columnGap also pads the screen edge, so panels gap away from the window border too
            padding = Padding(ThemeManager.current.columnGap)
        }
    }
}
