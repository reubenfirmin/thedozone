package tdz.view

import kotlinx.browser.window
import kotlinx.css.*
import kotlinx.css.properties.*
import kotlinx.html.div
import tdz.view.theme.ThemeManager
import web.cssom.ClassName
import web.dom.document
import web.html.HTMLElement
import zoned.framework.interop.addToBody
import zoned.framework.interop.classes
import zoned.framework.interop.css
import zoned.framework.interop.animation
import zoned.framework.interop.blur
import zoned.framework.interop.brightness
import zoned.framework.interop.conicGradient
import zoned.framework.interop.dropShadow
import zoned.framework.interop.filter
import zoned.framework.interop.keyframes
import zoned.framework.interop.radialGradient
import tdz.view.theme.themedStyleSheet
import zoned.framework.interop.transformOrigin
import zoned.framework.routing.Zone.Companion.zone

/** The two drop visuals for canvas links. Flip [WormholePortal.style] to A/B them. */
enum class PortalStyle { VORTEX, BLACK_HOLE }

/**
 * The drop visual for canvas links: a single shared, `position: fixed` effect pinned over whichever
 * canvas-link the dragged card is hovering, plus a "swallow" climax on drop. Two interchangeable
 * looks (see [style]), both tinted to the theme link color:
 *
 *  - [PortalStyle.VORTEX] — swirling conic-gradient rings around a glowing core; the card spirals in.
 *  - [PortalStyle.BLACK_HOLE] — a bright spinning accretion ring + black event horizon with a photon
 *    rim; on drop a shockwave rings out and the card spaghettifies into the core.
 *
 * Owned entirely here (overlay element + injected CSS + lifecycle); [tdz.controller.DragDrop] just
 * calls [showOver] / [hide] / [swallow].
 */
object WormholePortal {

    /** Flip this (and reload) to compare the two drop visuals. */
    var style: PortalStyle = PortalStyle.BLACK_HOLE

    private val ID get() = Zones.WORMHOLE.elementId

    /** The overlay element, built (or rebuilt with a typed zone swap) for the active [style]. */
    private fun ensureElement(): HTMLElement {
        ensureStyles()
        val el = Zones.WORMHOLE.resolve() ?: run {
            addToBody { div { zone(Zones.WORMHOLE) } }
            Zones.WORMHOLE.resolve() ?: error("wormhole zone failed to mount")
        }
        if (el.getAttribute("data-style") != style.name) {
            el.setAttribute("data-style", style.name)
            el.classes(if (style == PortalStyle.VORTEX) "tdz-wh-vortex" else "tdz-wh-bh")
            Zones.WORMHOLE.swap {
                when (style) {
                    PortalStyle.VORTEX -> {
                        div("tdz-wh-swirl")
                        div("tdz-wh-swirl2")
                        div("tdz-wh-core")
                    }
                    PortalStyle.BLACK_HOLE -> {
                        div("tdz-bh-glow")
                        div("tdz-bh-disk")
                        div("tdz-bh-core")
                        div("tdz-bh-shock")
                    }
                }
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
        portal.classList.remove(ClassName("tdz-wh-flash"))
        portal.classList.add(ClassName("tdz-wh-on"))
    }

    /**
     * Fade the hover state out. Deliberately leaves a running [swallow] flash alone — it removes only
     * the pulse class, not the flash, so a drop climax that just started still plays to completion.
     */
    fun hide() {
        Zones.WORMHOLE.resolve()?.classList?.remove(ClassName("tdz-wh-on"))
    }

    /**
     * Drop climax over [linkEl]: flash the effect and send a clone of [cardEl] into the core from
     * ([startCx], [startCy]) at size [w]×[h], then clean up. Cosmetic and fire-and-forget — the real
     * model move runs underneath. Explicit start geometry is passed in because [cardEl] is hidden
     * (zero-size) while it sits in the link drop zone. The card's motion depends on the [style].
     */
    fun swallow(linkEl: HTMLElement, cardEl: HTMLElement, startCx: Double, startCy: Double, w: Double, h: Double) {
        val portal = ensureElement()
        showOver(linkEl)
        portal.classList.remove(ClassName("tdz-wh-on"))
        portal.classList.add(ClassName("tdz-wh-flash"))

        val pr = linkEl.getBoundingClientRect()
        val pcx = pr.left + pr.width / 2
        val pcy = pr.top + pr.height / 2

        val el = cardEl.cloneNode(true) as HTMLElement
        el.removeAttribute("id")
        el.classList.remove(ClassName(CssClasses.CARD_GHOST), ClassName("sortable-ghost"))
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
        document.body?.appendChild(el)

        val dx = pcx - startCx
        val dy = pcy - startCy
        // transition is set now (before the element repaints); the transform changes in the next frame,
        // so the browser has a "from" state to animate from. el.style.* is the incremental DOM string
        // sink (css {} would replace the whole inline style and wipe the geometry set above).
        when (style) {
            PortalStyle.VORTEX -> {
                el.style.transition = listOf(
                    Transition("transform", 480.ms, cubicBezier(0.6, 0.05, 0.9, 0.30)),
                    Transition("opacity", 480.ms, Timing.easeOut),
                ).joinToString(", ")
                window.requestAnimationFrame {
                    el.style.transform = Transforms().apply {
                        translate(dx.px, dy.px); scale(0.04); rotate(540.deg)
                    }.toString()
                    el.style.opacity = "0"
                }
            }
            PortalStyle.BLACK_HOLE -> {
                // Two stages: stretch toward the hole (spaghettify), then collapse to a sliver at the core.
                el.style.transition = Transition("transform", 150.ms, Timing.easeIn).toString()
                window.requestAnimationFrame {
                    el.style.transform = Transforms().apply {
                        translate((dx * 0.55).px, (dy * 0.55).px); scale(0.45, 1.7)
                    }.toString()
                }
                window.setTimeout({
                    el.style.transition = listOf(
                        Transition("transform", 330.ms, cubicBezier(0.7, 0.0, 0.9, 0.4)),
                        Transition("opacity", 330.ms, Timing.easeOut),
                    ).joinToString(", ")
                    el.style.transform = Transforms().apply {
                        translate(dx.px, dy.px); scale(0.02, 0.05); rotate(18.deg)
                    }.toString()
                    el.style.opacity = "0"
                }, 160)
            }
        }
        window.setTimeout({ el.remove() }, 540)
    }

    /** Inject/refresh the CSS for both styles, tinted to the current theme link color. */
    private fun ensureStyles() {
        val c = ThemeManager.current.link
        val edge = Color("rgba(0,0,0,0)")
        val horizon = Color("rgba(0,0,0,0.92)")
        themedStyleSheet("tdz-wormhole-styles") {
            val spin = keyframes("tdz-wh-spin") { to { transform { rotate(360.deg) } } }
            val spinReverse = keyframes("tdz-wh-spin-rev") { to { transform { rotate((-360).deg) } } }
            val diskSpin = keyframes("tdz-bh-spin") { to { transform { rotate(360.deg) } } }
            val pulse = keyframes("tdz-wh-pulse") {
                at(0.pct, 100.pct) { transform { scale(1) } }
                at(50.pct) { transform { scale(1.07) } }
            }
            val vortexFlash = keyframes("tdz-wh-vflash") {
                at(0.pct) { transform { scale(1) }; filter(brightness(1), dropShadow(0.px, 0.px, 10.px, c)) }
                at(40.pct) { transform { scale(1.4) }; filter(brightness(2.4), dropShadow(0.px, 0.px, 28.px, c)) }
                at(100.pct) { transform { scale(0.7) }; opacity = 0.0; filter(brightness(1), dropShadow(0.px, 0.px, 10.px, c)) }
            }
            val holeFlash = keyframes("tdz-wh-bhflash") {
                at(0.pct) { transform { scale(1) }; filter(brightness(1), dropShadow(0.px, 0.px, 14.px, c)) }
                at(30.pct) { transform { scale(1.18) }; filter(brightness(2.6), dropShadow(0.px, 0.px, 30.px, c)) }
                at(100.pct) { transform { scale(0.55) }; opacity = 0.0; filter(brightness(1), dropShadow(0.px, 0.px, 14.px, c)) }
            }
            val shockwave = keyframes("tdz-bh-shock") {
                at(0.pct) { opacity = 0.9; transform { scale(0.2) } }
                at(100.pct) { opacity = 0.0; transform { scale(3) } }
            }

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
            rule("#$ID.tdz-wh-on") { opacity = 1.0 }
            rule("""#$ID .tdz-wh-swirl, #$ID .tdz-wh-swirl2, #$ID .tdz-wh-core,
                    #$ID .tdz-bh-glow, #$ID .tdz-bh-disk, #$ID .tdz-bh-core, #$ID .tdz-bh-shock""") {
                position = Position.absolute
                borderRadius = 50.pct
            }

            // ---- VORTEX ----
            rule("#$ID.tdz-wh-vortex") { filter(dropShadow(0.px, 0.px, 10.px, c)) }
            rule("#$ID.tdz-wh-vortex.tdz-wh-on") {
                animation(pulse, 1.6.s, Timing.easeInOut, iterationCount = IterationCount.infinite)
            }
            rule("#$ID .tdz-wh-swirl, #$ID .tdz-wh-core") { inset = Inset(0.px) }
            rule("#$ID .tdz-wh-swirl") {
                backgroundImage = conicGradient(from = 0.deg) {
                    stop(Color.transparent, 0.deg); stop(c, 60.deg); stop(Color.transparent, 140.deg)
                    stop(c, 220.deg); stop(Color.transparent, 320.deg)
                }
                animation(spin, 1.1.s, Timing.linear, iterationCount = IterationCount.infinite)
                filter(blur(1.px))
            }
            rule("#$ID .tdz-wh-swirl2") {
                inset = Inset(16.pct)
                opacity = 0.85
                backgroundImage = conicGradient(from = 90.deg) {
                    stop(Color.transparent, 0.deg); stop(c, 50.deg); stop(Color.transparent, 120.deg)
                    stop(c, 200.deg); stop(Color.transparent, 300.deg)
                }
                animation(spinReverse, 0.75.s, Timing.linear, iterationCount = IterationCount.infinite)
            }
            rule("#$ID .tdz-wh-core") {
                backgroundImage = radialGradient(at = "center") {
                    stop(Color.white, 0.pct); stop(c, 12.pct); stop(horizon, 46.pct); stop(edge, 72.pct)
                }
            }
            rule("#$ID.tdz-wh-vortex.tdz-wh-flash") {
                opacity = 1.0
                animation(vortexFlash, 480.ms, Timing.easeOut, fillMode = FillMode.forwards)
            }

            // ---- BLACK HOLE ----
            rule("#$ID.tdz-wh-bh") { filter(dropShadow(0.px, 0.px, 14.px, c)) }
            rule("#$ID.tdz-wh-bh.tdz-wh-on") {
                animation(pulse, 2.2.s, Timing.easeInOut, iterationCount = IterationCount.infinite)
            }
            rule("#$ID .tdz-bh-glow") {
                inset = Inset((-22).pct)
                filter(blur(7.px))
                opacity = 0.55
                backgroundImage = radialGradient { stop(c, 0.pct); stop(edge, 62.pct) }
            }
            rule("#$ID .tdz-bh-disk") {
                inset = Inset(0.px)
                filter(blur(2.px))
                animation(diskSpin, 0.9.s, Timing.linear, iterationCount = IterationCount.infinite)
                backgroundImage = conicGradient(from = 0.deg) {
                    stop(edge, 0.deg); stop(c, 40.deg); stop(Color.white, 70.deg); stop(c, 110.deg)
                    stop(edge, 170.deg); stop(c, 250.deg); stop(Color.white, 290.deg); stop(edge, 340.deg)
                }
            }
            rule("#$ID .tdz-bh-core") {
                inset = Inset(23.pct)
                backgroundImage = radialGradient(at = "center") {
                    stop(Color.black, 50.pct); stop(c, 59.pct); stop(Color.white, 66.pct); stop(edge, 76.pct)
                }
                boxShadow += BoxShadowInset(Color("#000"), 0.px, 0.px, 12.px, 0.px)
                boxShadow += BoxShadow(c, 0.px, 0.px, 16.px, 0.px)
            }
            rule("#$ID .tdz-bh-shock") {
                inset = Inset(30.pct)
                border = Border(2.px, BorderStyle.solid, c)
                opacity = 0.0
                transform { scale(0.2) }
            }
            rule("#$ID.tdz-wh-bh.tdz-wh-flash") {
                opacity = 1.0
                animation(holeFlash, 520.ms, Timing.easeOut, fillMode = FillMode.forwards)
            }
            rule("#$ID.tdz-wh-bh.tdz-wh-flash .tdz-bh-shock") {
                animation(shockwave, 520.ms, Timing.easeOut, fillMode = FillMode.forwards)
            }
        }
    }
}
