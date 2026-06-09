package tdz.view

import kotlinx.browser.window
import kotlinx.css.*
import kotlinx.css.properties.*
import tdz.view.theme.ThemeManager
import web.dom.ElementId
import web.dom.document
import web.html.HTMLElement
import web.html.HTMLStyleElement
import zoned.framework.interop.css
import zoned.framework.interop.getHtmlElement
import zoned.framework.interop.transformOrigin

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

    private const val ID = "tdz-wormhole"

    private fun structureFor(s: PortalStyle) = when (s) {
        PortalStyle.VORTEX ->
            "<div class='tdz-wh-swirl'></div><div class='tdz-wh-swirl2'></div><div class='tdz-wh-core'></div>"
        PortalStyle.BLACK_HOLE ->
            "<div class='tdz-bh-glow'></div><div class='tdz-bh-disk'></div><div class='tdz-bh-core'></div><div class='tdz-bh-shock'></div>"
    }

    /** The overlay element, built (or rebuilt) for the active [style]. */
    private fun ensureElement(): HTMLElement {
        ensureStyles()
        val el = getHtmlElement(ID) ?: run {
            val created = document.createElement("div") as HTMLElement
            created.id = ElementId(ID)
            document.body?.appendChild(created)
            created
        }
        if (el.getAttribute("data-style") != style.name) {
            el.setAttribute("data-style", style.name)
            el.asDynamic().className = if (style == PortalStyle.VORTEX) "tdz-wh-vortex" else "tdz-wh-bh"
            el.asDynamic().innerHTML = structureFor(style)
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
        portal.asDynamic().classList.remove("tdz-wh-flash")
        portal.asDynamic().classList.add("tdz-wh-on")
    }

    /**
     * Fade the hover state out. Deliberately leaves a running [swallow] flash alone — it removes only
     * the pulse class, not the flash, so a drop climax that just started still plays to completion.
     */
    fun hide() {
        (getHtmlElement(ID))?.asDynamic()?.classList?.remove("tdz-wh-on")
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
        portal.asDynamic().classList.remove("tdz-wh-on")
        portal.asDynamic().classList.add("tdz-wh-flash")

        val pr = linkEl.getBoundingClientRect()
        val pcx = pr.left + pr.width / 2
        val pcy = pr.top + pr.height / 2

        val clone = cardEl.asDynamic().cloneNode(true)
        clone.removeAttribute("id")
        clone.classList.remove("tdz-card-ghost")
        clone.classList.remove("sortable-ghost")
        val el = clone.unsafeCast<HTMLElement>()
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
            zIndex = 1000001   // just above the portal, so the swallow animation reads on top of it
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
        val c = ThemeManager.current.link.toString()
        val css = """
            /* z-index sits above Sortable's drag mirror (~100000) so the portal glows on top of the
               dragged card — a large card must not cover the hole it's being dropped into. */
            #$ID { position: fixed; pointer-events: none; z-index: 1000000; border-radius: 50%;
                   opacity: 0; transition: opacity 130ms ease; }
            #$ID.tdz-wh-on { opacity: 1; }
            #$ID .tdz-wh-swirl, #$ID .tdz-wh-swirl2, #$ID .tdz-wh-core,
            #$ID .tdz-bh-glow, #$ID .tdz-bh-disk, #$ID .tdz-bh-core, #$ID .tdz-bh-shock {
                   position: absolute; border-radius: 50%; }

            /* ---- VORTEX ---- */
            #$ID.tdz-wh-vortex { filter: drop-shadow(0 0 10px $c); }
            #$ID.tdz-wh-vortex.tdz-wh-on { animation: tdz-wh-pulse 1.6s ease-in-out infinite; }
            #$ID .tdz-wh-swirl, #$ID .tdz-wh-core { inset: 0; }
            #$ID .tdz-wh-swirl {
                   background: conic-gradient(from 0deg, transparent 0deg, $c 60deg, transparent 140deg, $c 220deg, transparent 320deg);
                   animation: tdz-wh-spin 1.1s linear infinite; filter: blur(1px); }
            #$ID .tdz-wh-swirl2 { inset: 16%; opacity: 0.85;
                   background: conic-gradient(from 90deg, transparent 0deg, $c 50deg, transparent 120deg, $c 200deg, transparent 300deg);
                   animation: tdz-wh-spin-rev 0.75s linear infinite; }
            #$ID .tdz-wh-core {
                   background: radial-gradient(circle at center, #ffffff 0%, $c 12%, rgba(0,0,0,0.92) 46%, rgba(0,0,0,0) 72%); }
            #$ID.tdz-wh-vortex.tdz-wh-flash { opacity: 1; animation: tdz-wh-vflash 480ms ease-out forwards; }

            /* ---- BLACK HOLE ---- */
            #$ID.tdz-wh-bh { filter: drop-shadow(0 0 14px $c); }
            #$ID.tdz-wh-bh.tdz-wh-on { animation: tdz-wh-pulse 2.2s ease-in-out infinite; }
            #$ID .tdz-bh-glow { inset: -22%; filter: blur(7px); opacity: 0.55;
                   background: radial-gradient(circle, $c 0%, rgba(0,0,0,0) 62%); }
            #$ID .tdz-bh-disk { inset: 0; filter: blur(2px); animation: tdz-bh-spin 0.9s linear infinite;
                   background: conic-gradient(from 0deg, rgba(0,0,0,0) 0deg, $c 40deg, #ffffff 70deg, $c 110deg,
                                              rgba(0,0,0,0) 170deg, $c 250deg, #ffffff 290deg, rgba(0,0,0,0) 340deg); }
            #$ID .tdz-bh-core { inset: 23%;
                   background: radial-gradient(circle at center, #000 50%, $c 59%, #ffffff 66%, rgba(0,0,0,0) 76%);
                   box-shadow: inset 0 0 12px #000, 0 0 16px $c; }
            #$ID .tdz-bh-shock { inset: 30%; border: 2px solid $c; opacity: 0; transform: scale(0.2); }
            #$ID.tdz-wh-bh.tdz-wh-flash { opacity: 1; animation: tdz-wh-bhflash 520ms ease-out forwards; }
            #$ID.tdz-wh-bh.tdz-wh-flash .tdz-bh-shock { animation: tdz-bh-shock 520ms ease-out forwards; }

            @keyframes tdz-wh-spin { to { transform: rotate(360deg); } }
            @keyframes tdz-wh-spin-rev { to { transform: rotate(-360deg); } }
            @keyframes tdz-bh-spin { to { transform: rotate(360deg); } }
            @keyframes tdz-wh-pulse { 0%,100% { transform: scale(1); } 50% { transform: scale(1.07); } }
            @keyframes tdz-wh-vflash {
                   0% { transform: scale(1); filter: brightness(1) drop-shadow(0 0 10px $c); }
                   40% { transform: scale(1.4); filter: brightness(2.4) drop-shadow(0 0 28px $c); }
                   100% { transform: scale(0.7); opacity: 0; filter: brightness(1) drop-shadow(0 0 10px $c); } }
            @keyframes tdz-wh-bhflash {
                   0% { transform: scale(1); filter: brightness(1) drop-shadow(0 0 14px $c); }
                   30% { transform: scale(1.18); filter: brightness(2.6) drop-shadow(0 0 30px $c); }
                   100% { transform: scale(0.55); opacity: 0; filter: brightness(1) drop-shadow(0 0 14px $c); } }
            @keyframes tdz-bh-shock { 0% { opacity: 0.9; transform: scale(0.2); } 100% { opacity: 0; transform: scale(3); } }
        """.trimIndent()
        val styleEl = (getHtmlElement("tdz-wormhole-styles") as? HTMLStyleElement)
            ?: (document.createElement("style") as HTMLStyleElement).also {
                it.id = ElementId("tdz-wormhole-styles")
                document.head.appendChild(it)
            }
        styleEl.textContent = css
    }
}
