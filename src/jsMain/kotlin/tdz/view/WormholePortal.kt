package tdz.view

import kotlinx.browser.window
import kotlinx.css.*
import kotlinx.css.properties.*
import kotlinx.html.div
import tdz.model.CanvasName
import tdz.view.theme.ThemeManager
import web.cssom.ClassName
import web.dom.document
import web.html.HTMLElement
import zoned.framework.interop.addToBody
import zoned.framework.interop.classes
import zoned.framework.interop.css
import zoned.framework.interop.animation
import zoned.framework.interop.brightness
import zoned.framework.interop.dropShadow
import zoned.framework.interop.filter
import zoned.framework.interop.keyframes
import tdz.view.theme.themedStyleSheet
import zoned.framework.interop.transformOrigin
import zoned.framework.routing.Zone.Companion.zone

/**
 * The drop visual for canvas links: a single shared, `position: fixed` effect pinned over whichever
 * canvas-link the dragged card is hovering, plus a "swallow" climax on drop. The look is an arcade
 * wireframe tunnel — concentric stroked diamonds receding into a glowing core — tinted to the theme
 * link color.
 *
 * Owned entirely here (overlay element + injected CSS + lifecycle); [tdz.controller.DragDrop] just
 * calls [showOver] / [hide] / [swallow].
 */
object WormholePortal {

    private val ID get() = Zones.WORMHOLE.elementId

    // Wireframe-tunnel class names, each used in BOTH the kotlinx.html markup and a CSS selector below.
    private const val VEC_RING = "tdz-vec-ring"
    private const val VEC_R1 = "tdz-vec-r1"
    private const val VEC_R2 = "tdz-vec-r2"
    private const val VEC_R3 = "tdz-vec-r3"
    private const val VEC_R4 = "tdz-vec-r4"
    private const val VEC_CORE = "tdz-vec-core"
    private const val VEC_SHOCK = "tdz-vec-shock"
    private const val WH_VEC = "tdz-wh-vec"
    private const val WH_ON = "tdz-wh-on"
    private const val WH_FLASH = "tdz-wh-flash"
    private const val WH_LABEL = "tdz-wh-label"
    /** External SortableJS class on the dragged-card mirror — owned by Sortable, mirrored here exactly. */
    private const val SORTABLE_GHOST = "sortable-ghost"

    /** The overlay element, built once with its wireframe-tunnel children. */
    private fun ensureElement(): HTMLElement {
        ensureStyles()
        val el = Zones.WORMHOLE.resolve() ?: run {
            addToBody { div { zone(Zones.WORMHOLE) } }
            Zones.WORMHOLE.resolve() ?: error("wormhole zone failed to mount")
        }
        if (el.getAttribute("data-built") == null) {
            el.setAttribute("data-built", "1")
            el.classes(WH_VEC)
            Zones.WORMHOLE.swap {
                // A wireframe tunnel: concentric stroked diamonds receding into a glowing core.
                div("$VEC_RING $VEC_R1")
                div("$VEC_RING $VEC_R2")
                div("$VEC_RING $VEC_R3")
                div("$VEC_RING $VEC_R4")
                div(VEC_CORE)
                div(VEC_SHOCK)
                // The destination board name, shown on the portal (which sits ABOVE the dragged card
                // mirror that otherwise hides the targeted link) so it's clear which board you're in.
                div(WH_LABEL)
            }
        }
        return el
    }

    /** Show the effect centred on [linkEl] (a canvas-link drop zone), enlarging past the small chip. */
    fun showOver(linkEl: HTMLElement) {
        val portal = ensureElement()
        val r = linkEl.getBoundingClientRect()
        val size = maxOf(r.height * 2.6, 68.0)
        val cx = r.left + r.width / 2
        val cy = r.top + r.height / 2
        portal.css {
            left = (cx - size / 2).px
            top = (cy - size / 2).px
            width = size.px
            height = size.px
        }
        // Name the destination on the portal (the chip itself is hidden under the dragged card).
        val target = linkEl.getAttribute("data-canvas-name")?.let { CanvasName(it) }
        portal.querySelector(".$WH_LABEL")?.textContent = target?.let { "→ ${omniboxLabel(it)}" } ?: ""
        portal.classList.remove(ClassName(WH_FLASH))
        portal.classList.add(ClassName(WH_ON))
    }

    /**
     * Fade the hover state out. Deliberately leaves a running [swallow] flash alone — it removes only
     * the pulse class, not the flash, so a drop climax that just started still plays to completion.
     */
    fun hide() {
        Zones.WORMHOLE.resolve()?.classList?.remove(ClassName(WH_ON))
    }

    /**
     * Drop climax over [linkEl]: flash the effect and send a clone of [cardEl] into the core from
     * ([startCx], [startCy]) at size [w]×[h], then clean up. Cosmetic and fire-and-forget — the real
     * model move runs underneath. Explicit start geometry is passed in because [cardEl] is hidden
     * (zero-size) while it sits in the link drop zone.
     */
    fun swallow(linkEl: HTMLElement, cardEl: HTMLElement, startCx: Double, startCy: Double, w: Double, h: Double) {
        val portal = ensureElement()
        showOver(linkEl)
        portal.classList.remove(ClassName(WH_ON))
        portal.classList.add(ClassName(WH_FLASH))

        val pr = linkEl.getBoundingClientRect()
        val pcx = pr.left + pr.width / 2
        val pcy = pr.top + pr.height / 2

        val el = cardEl.cloneNode(true) as HTMLElement
        el.removeAttribute("id")
        el.classList.remove(ClassName(CssClasses.CARD_GHOST), ClassName(SORTABLE_GHOST))
        el.css {
            position = Position.fixed
            left = (startCx - w / 2).px
            top = (startCy - h / 2).px
            width = w.px
            height = h.px
            margin = Margin(0.px)
            display = Display.block
            visibility = Visibility.visible
            opacity = 1.0
            pointerEvents = PointerEvents.none
            zIndex = ZLayer.WORMHOLE_CARD   // just above the portal, so the swallow animation reads on top of it
            transformOrigin("center center")
        }
        document.body.appendChild(el)

        val dx = pcx - startCx
        val dy = pcy - startCy
        // transition is set now (before the element repaints); the transform changes in the next frame,
        // so the browser has a "from" state to animate from. el.style.* is the incremental DOM string
        // sink (css {} would replace the whole inline style and wipe the geometry set above).
        // Warp in: spin down into the core, matching the wireframe tunnel's inward pull.
        el.style.transition = listOf(
            Transition("transform", 460.ms, cubicBezier(0.6, 0.05, 0.9, 0.30)),
            Transition("opacity", 460.ms, Timing.easeOut),
        ).joinToString(", ")
        window.requestAnimationFrame {
            el.style.transform = Transforms().apply {
                translate(dx.px, dy.px); scale(0.03); rotate(420.deg)
            }.toString()
            el.style.opacity = "0"
        }
        window.setTimeout({ el.remove() }, 540)
    }

    /** Inject/refresh the wireframe-tunnel CSS, tinted to the current theme link color. */
    private fun ensureStyles() {
        val c = ThemeManager.current.link
        themedStyleSheet("tdz-wormhole-styles") {
            // z-index sits above Sortable's drag mirror (~100000) so the portal glows on top of the
            // dragged card — a large card must not cover the hole it's being dropped into.
            rule("#$ID") {
                position = Position.fixed
                pointerEvents = PointerEvents.none
                zIndex = ZLayer.WORMHOLE
                borderRadius = 50.pct
                opacity = 0.0
                transition += Transition("opacity", 130.ms, Timing.ease)
            }
            rule("#$ID.$WH_ON") { opacity = 1.0 }
            // Destination name, pinned just below the portal — a high-contrast pill (readable on any
            // theme/background) accented with the link color. Sits above the dragged card mirror.
            rule("#$ID .$WH_LABEL") {
                position = Position.absolute
                top = 100.pct
                left = 50.pct
                transform { translateX((-50).pct) }
                marginTop = 8.px
                whiteSpace = WhiteSpace.nowrap
                backgroundColor = Color("rgba(15,15,20,0.92)")
                color = Color.white
                border = Border(1.px, BorderStyle.solid, c)
                padding = Padding(3.px, 9.px)
                borderRadius = 12.px
                fontSize = 12.px
                fontWeight = FontWeight.bold
                fontFamily = ThemeManager.current.fontFamily
            }

            // ---- Arcade wireframe tunnel ----
            // Stroked diamonds receding into the core — no filled gradients, so it reads as 2D vector
            // line art (Tempest/Asteroids) rather than a spinning disc. The neon glow is a drop-shadow.
            val vecTunnel = keyframes("tdz-vec-tunnel") {
                at(0.pct) { opacity = 0.0; transform { rotate(30.deg); scale(1.3) } }
                at(12.pct) { opacity = 1.0; transform { rotate(36.deg); scale(1.06) } }
                at(86.pct) { opacity = 0.9; transform { rotate(80.deg); scale(0.12) } }
                at(100.pct) { opacity = 0.0; transform { rotate(90.deg); scale(0.0) } }
            }
            val vecShockwave = keyframes("tdz-vec-shockwave") {
                at(0.pct) { opacity = 0.9; transform { rotate(45.deg); scale(0.25) } }
                at(100.pct) { opacity = 0.0; transform { rotate(45.deg); scale(2.6) } }
            }
            val vecFlash = keyframes("tdz-vec-flash") {
                at(0.pct) { filter(brightness(1), dropShadow(0.px, 0.px, 12.px, c)) }
                at(35.pct) { transform { scale(1.25) }; filter(brightness(2.4), dropShadow(0.px, 0.px, 30.px, c)) }
                at(100.pct) { opacity = 0.0; transform { scale(0.6) }; filter(brightness(1), dropShadow(0.px, 0.px, 12.px, c)) }
            }

            rule("#$ID.$WH_VEC") { filter(dropShadow(0.px, 0.px, 9.px, c)) }   // neon glow on the wireframe
            rule("#$ID .$VEC_RING, #$ID .$VEC_CORE, #$ID .$VEC_SHOCK") {
                position = Position.absolute
                inset = Inset(0.px)
            }
            // Diamonds (a square is a diamond at 45°); transparent fill = see-through wireframe.
            rule("#$ID .$VEC_RING") {
                border = Border(2.px, BorderStyle.solid, c)
                animation(vecTunnel, 1.7.s, Timing.linear, iterationCount = IterationCount.infinite)
            }
            // Stagger the rings a quarter-cycle apart so they stream inward continuously.
            rule("#$ID .$VEC_R2") { animation(vecTunnel, 1.7.s, Timing.linear, delay = 0.42.s, iterationCount = IterationCount.infinite) }
            rule("#$ID .$VEC_R3") { animation(vecTunnel, 1.7.s, Timing.linear, delay = 0.85.s, iterationCount = IterationCount.infinite) }
            rule("#$ID .$VEC_R4") { animation(vecTunnel, 1.7.s, Timing.linear, delay = 1.27.s, iterationCount = IterationCount.infinite) }
            rule("#$ID .$VEC_CORE") {
                inset = Inset(40.pct)
                backgroundColor = c
                transform { rotate(45.deg) }
                boxShadow += BoxShadow(c, 0.px, 0.px, 12.px, 0.px)
            }
            rule("#$ID .$VEC_SHOCK") {
                border = Border(2.px, BorderStyle.solid, c)
                opacity = 0.0
                transform { rotate(45.deg); scale(0.25) }
            }
            rule("#$ID.$WH_VEC.$WH_FLASH") {
                opacity = 1.0
                animation(vecFlash, 500.ms, Timing.easeOut, fillMode = FillMode.forwards)
            }
            rule("#$ID.$WH_VEC.$WH_FLASH .$VEC_SHOCK") {
                animation(vecShockwave, 500.ms, Timing.easeOut, fillMode = FillMode.forwards)
            }
        }
    }
}
