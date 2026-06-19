package tdz.view

import kotlinx.css.*
import kotlinx.css.properties.*
import kotlinx.html.*
import tdz.view.theme.ThemeManager
import tdz.view.theme.themedStyleSheet
import web.cssom.ClassName
import web.html.HTMLElement
import zoned.framework.dom.Ref
import zoned.framework.dom.onClick
import zoned.framework.dom.ref
import zoned.framework.interop.BackfaceVisibility
import zoned.framework.interop.TransformStyle
import zoned.framework.interop.backfaceVisibility
import zoned.framework.interop.transformStyle
import zoned.framework.interop.transitionNone

/**
 * The flip-card widget — a self-contained, stateful 3D-flip card extracted from [CardRenderer].
 *
 * A flip card is a genuine 3D flip: the whole CARD element turns 180° about its vertical axis —
 * border, fill, and shadow with it (it keeps the theme's normal card rules, no special bg). It owns
 * its own imperative turn animation (setTimeout-paced swap, getBoundingClientRect height-pinning,
 * transition toggling) and its own themed CSS rules; the content of each side is supplied by the
 * caller via [renderSide], so this widget stays decoupled from the segment renderer.
 */
object FlipCard {

    /** Flip-card turn duration; the CSS transition and the JS swap timing share this value. */
    private const val FLIP_MS = 360

    /**
     * Build the flip card into the receiving card [DIV].
     *
     * Depth comes from `perspective(...)` INSIDE the card's own transform (not on an ancestor) — the
     * column is a scroll container, which would otherwise flatten the 3D. The front face is in flow
     * and sizes the card; the back is overlaid and pre-rotated 180° so it reads forward once turned.
     * One click turns the card to 180° (revealing the back) while the card's HEIGHT animates from the
     * front's to the back's measured height, so it grows/shrinks smoothly DURING the flip rather than
     * after. At the end, with the transition suppressed, the card snaps back to 0° and promotes the
     * revealed side to the front (height back to auto) in the same frame, so nothing jumps. N sides —
     * the back is always the NEXT one.
     *
     * When the theme's card has no visible border (e.g. Long Form), a transient outline
     * ([CssClasses.FLIP_RING]) is added for the duration of the turn so the turning edge is visible.
     * State is DOM-local and ephemeral: a column re-render restarts at side 0.
     *
     * [renderSide] emits one side's content into the side container the widget creates.
     */
    fun DIV.buildFlipCard(
        sides: Int,
        renderSide: FlowContent.(sideIndex: Int) -> Unit,
    ) {
        ensureFlipStyles()
        // Mark the card element itself as the rotating surface and capture it — `ref` here binds to
        // the card div we're building into.
        classes = classes + CssClasses.FLIP
        val cardRef = Ref<HTMLElement>()
        ref(cardRef)

        // If the theme draws no visible card border, the turning edge would be invisible; add a
        // transient outline just while turning so the flip reads.
        val t = ThemeManager.current
        val needsRing = t.cardBorderInvisible

        val n = sides
        val btnRef = Ref<HTMLElement>()
        val sideRefs = mutableListOf<Ref<HTMLElement>>()
        var current = 0
        var animating = false

        button(classes = CssClasses.FLIP_BTN) {
            ref(btnRef)
            +"↻ 1/$n"
            attributes["aria-label"] = "Flip card"
            onClick { ev ->
                // Never let a flip start a drag or the column's double-click-to-edit.
                ev.stopPropagation(); ev.preventDefault()
                if (animating || n < 2) return@onClick
                animating = true
                val next = (current + 1) % n
                val el = cardRef.element
                // Animate the card height from the current side to the incoming side (the back face,
                // overlaid with height:auto, so its offsetHeight is the natural target) so it grows
                // smoothly during the turn instead of snapping after.
                val startH = el.offsetHeight
                val endH = sideRefs[next].element.offsetHeight
                el.style.height = "${startH}px"
                el.getBoundingClientRect()                              // pin the start height
                if (needsRing) el.classList.add(ClassName(CssClasses.FLIP_RING))
                el.classList.add(ClassName(CssClasses.FLIP_TURN))      // turn 0° -> 180°
                el.style.height = "${endH}px"                          // ...while height eases to the back's
                kotlinx.browser.window.setTimeout({
                    // The card may have been re-rendered (canvas/theme swap) mid-turn — never touch a
                    // detached node; the fresh render carries its own flip state.
                    if (!el.isConnected) return@setTimeout
                    current = next
                    // Snap back to 0° with the transition suppressed (inline, like the wormhole), promote
                    // the revealed side to the front, and release the explicit height — all in one frame,
                    // so nothing jumps; then restore the stylesheet transition for the next turn.
                    el.style.transition = "none"
                    el.classList.remove(ClassName(CssClasses.FLIP_TURN))
                    setFaces(sideRefs, current, (current + 1) % n)
                    el.style.height = ""                                // back to auto (natural for the new front)
                    el.getBoundingClientRect()                          // commit the 0° / no-transition state
                    el.style.transition = ""                            // back to the stylesheet's transition
                    if (needsRing) el.classList.remove(ClassName(CssClasses.FLIP_RING))
                    btnRef.element.textContent = "↻ ${current + 1}/$n"
                    animating = false
                }, FLIP_MS)
            }
        }
        for (i in 0 until n) {
            val sideRef = Ref<HTMLElement>()
            sideRefs.add(sideRef)
            // Initial faces set here (not just in JS) so there's no flash before mount: side 0 faces
            // the viewer, side 1 is the overlaid back, the rest are hidden.
            val face = when (i) {
                0 -> CssClasses.FLIP_FRONT
                1 -> CssClasses.FLIP_BACKFACE
                else -> CssClasses.FLIP_HIDDEN
            }
            div("${CssClasses.FLIP_SIDE} $face") {
                ref(sideRef)
                renderSide(i)
            }
        }
    }

    /** Make side [front] the viewer-facing (in-flow) face, side [back] the overlaid incoming face, and
     *  hide every other side — toggling typed classes only, no inline style. */
    private fun setFaces(sideRefs: List<Ref<HTMLElement>>, front: Int, back: Int) {
        sideRefs.forEachIndexed { i, r ->
            val cl = r.element.classList
            cl.remove(ClassName(CssClasses.FLIP_FRONT))
            cl.remove(ClassName(CssClasses.FLIP_BACKFACE))
            cl.remove(ClassName(CssClasses.FLIP_HIDDEN))
            cl.add(ClassName(when (i) {
                front -> CssClasses.FLIP_FRONT
                back -> CssClasses.FLIP_BACKFACE
                else -> CssClasses.FLIP_HIDDEN
            }))
        }
    }

    private fun ensureFlipStyles() {
        val t = ThemeManager.current
        themedStyleSheet("tdz-flip-styles") {
            // The whole card turns. Depth comes from perspective() in the card's OWN transform (an
            // ancestor `perspective` would be flattened by the scrolling column); preserve-3d keeps the
            // two faces in the card's 3D space so the back face's backface-visibility works. Fill /
            // border / shadow are the theme's ordinary `.tdz-card` — no special bg. Both transform and
            // height transition, so the card grows/shrinks smoothly as it turns.
            val ease = cubicBezier(0.45, 0.05, 0.25, 1.0)
            rule(".${CssClasses.CARD}.${CssClasses.FLIP}") {
                position = Position.relative
                padding = Padding(0.px)
                // border-box so the pinned/animated height (measured via offsetHeight, which includes
                // the border) matches exactly — otherwise the card jumps when the height is pinned.
                boxSizing = BoxSizing.borderBox
                // content-visibility:auto (on the base .tdz-card) applies paint containment, which
                // FORCES transform-style:flat and would silently kill the 3D — opt back out here.
                contentVisibility = ContentVisibility.visible
                transformStyle(TransformStyle.PRESERVE_3D)
                transform { perspective(900.px); rotateY(0.deg) }
                transition += Transition("transform", FLIP_MS.ms, ease)
                transition += Transition("height", FLIP_MS.ms, ease)
            }
            rule(".${CssClasses.CARD}.${CssClasses.FLIP}.${CssClasses.FLIP_TURN}") {
                transform { perspective(900.px); rotateY(180.deg) }
            }
            // When the theme draws no visible card border, add a real one to the card for the duration
            // of the turn so the flipping edge reads. box-sizing:border-box (above) means coloring the
            // 1px border in causes no layout shift.
            rule(".${CssClasses.CARD}.${CssClasses.FLIP}.${CssClasses.FLIP_RING}") {
                border = Border(1.px, BorderStyle.solid, t.fg)
            }
            rule(".${CssClasses.FLIP_SIDE}") {
                padding = Padding(t.cardPadding)
                boxSizing = BoxSizing.borderBox
                backfaceVisibility(BackfaceVisibility.HIDDEN)
            }
            // Front is in flow (sizes the card when height is auto); back is overlaid with height:auto
            // so its measured offsetHeight is the natural target for the height animation.
            rule(".${CssClasses.FLIP_FRONT}") { position = Position.relative }
            rule(".${CssClasses.FLIP_BACKFACE}") {
                position = Position.absolute
                top = 0.px; left = 0.px
                width = 100.pct
                transform { rotateY(180.deg) }
                pointerEvents = PointerEvents.none
            }
            rule(".${CssClasses.FLIP_HIDDEN}") { display = Display.none }
            // The drag mirror (Sortable lifts a clone to <body>, class CARD_DRAG): flatten the 3D and
            // drop the transition so the clone renders and tracks the cursor without lag.
            rule(".${CssClasses.CARD}.${CssClasses.FLIP}.${CssClasses.CARD_DRAG}") {
                transform = Transforms.none
                transformStyle(TransformStyle.FLAT)
                transitionNone()
            }
            rule(".${CssClasses.FLIP_BTN}") {
                position = Position.absolute
                top = 4.px; right = 4.px
                zIndex = 2
                cursor = Cursor.pointer
                padding = Padding(1.px, 7.px)
                fontSize = 11.px
                fontFamily = t.fontFamily
                lineHeight = LineHeight("1.4")
                backgroundColor = t.menuBg
                color = t.menuFg
                border = Border(1.px, BorderStyle.solid, t.menuBorder)
                borderRadius = 10.px
                opacity = 0.65
                transition += Transition("opacity", 120.ms, Timing.easeOut)
            }
            rule(".${CssClasses.FLIP_BTN}:hover") { opacity = 1.0 }
            // The arrow turns (and reads backwards) with the card, so hide it for the duration of the
            // flip; it fades back in once the card has settled. Outranks the base + hover rules.
            rule(".${CssClasses.CARD}.${CssClasses.FLIP}.${CssClasses.FLIP_TURN} .${CssClasses.FLIP_BTN}") {
                opacity = 0.0
            }
        }
    }
}
