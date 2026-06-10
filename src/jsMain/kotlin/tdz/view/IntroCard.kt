package tdz.view

import kotlinx.html.ButtonType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.span
import kotlinx.html.unsafe
import tdz.model.Canvas
import tdz.model.Model
import tdz.rootCanvas
import kotlinx.css.*
import kotlinx.css.properties.*
import tdz.view.theme.ThemeManager
import zoned.framework.dom.onClick
import zoned.framework.interop.addToBody
import zoned.framework.interop.animation
import zoned.framework.interop.keyframes
import zoned.framework.interop.styleSheet
import zoned.framework.routing.Zone.Companion.zone

/**
 * A first-run / empty-state "welcome" card, centred over the canvas while it is in its pristine state:
 * a single content column with no cards. Splitting into a second column — or adding a card — ends that
 * state and hides the card; deleting back down to one empty column brings it back. Purely a view hint
 * (no persistence).
 *
 * The overlay is `pointer-events: none`, so a right-click passes straight through to the canvas and
 * opens the context menu — exactly what the card tells the user to do. The instruction adapts to the
 * pointing device (see [PointerHint]): "Right-click" by default, "Two-finger tap" once a trackpad is
 * detected (plus "Control-click" on macOS).
 *
 * [evaluate] is driven from [CanvasColumns] on every column (re)render. Self-contained: owns its
 * body-level zone ([Zones.INTRO]) + injected CSS.
 */
object IntroCard {

    private val ID get() = Zones.INTRO.elementId

    /**
     * Show the welcome card iff [canvas] is the ROOT board in its pristine state (a single, empty content
     * column); otherwise remove it. Child boards starting empty is normal navigation, not a first run, so
     * they never get the card.
     */
    fun evaluate(model: Model, canvas: Canvas) {
        val leaves = canvas.columns.filter { !canvas.layoutMap.containsKey(it) }
        val pristine = canvas.name == rootCanvas &&
            leaves.size == 1 && model.cards(canvas.name, leaves[0]).isEmpty()
        if (pristine) show() else remove()
    }

    private var hintHooked = false

    private fun show() {
        ensureStyles()                 // refresh each time so a theme switch while empty recolors the card
        PointerHint.start()            // begin sniffing wheel events to refine the instruction
        hookPointerHint()
        if (Zones.INTRO.resolve() != null) return
        addToBody {
            div {
                zone(Zones.INTRO)
                div("tdz-intro-card") {
                    // The mouse SVG stays a raw fragment: kotlinx.html builds elements in the HTML
                    // namespace, so <path>/<rect> built via the DSL would not render; the innerHTML
                    // parser handles the SVG namespace switch.
                    unsafe { raw(mouseSvg()) }
                    div("tdz-intro-text") {
                        div("tdz-intro-h") { +"Welcome to The Do Zone" }
                        div("tdz-intro-p") {
                            span("tdz-intro-accent") { +instruction() }
                            +" anywhere to open the menu and get started."
                        }
                        // The card itself is click-through (pointer-events: none); the button opts
                        // back in (see CSS) so it's the one clickable affordance. Opens the
                        // (stubbed) examples wizard.
                        button(classes = "tdz-intro-btn", type = ButtonType.button) {
                            +"See examples"
                            onClick { ExamplesWizard.show() }
                        }
                    }
                }
            }
        }
    }

    private fun remove() {
        Zones.INTRO.resolve()?.remove()
    }

    /** Once the pointing device is (re)classified, live-update the instruction verb if the card is up. */
    private fun hookPointerHint() {
        if (hintHooked) return
        hintHooked = true
        PointerHint.onChange {
            Zones.INTRO.resolve()?.querySelector(".tdz-intro-accent")?.textContent = instruction()
        }
    }

    /** The right-click verb, adapted to the detected pointing device. */
    private fun instruction(): String = when {
        PointerHint.kind == PointerHint.Kind.TRACKPAD && PointerHint.isMac -> "Two-finger tap (or Control-click)"
        PointerHint.kind == PointerHint.Kind.TRACKPAD -> "Two-finger tap"
        else -> "Right-click"
    }

    /**
     * A mouse whose top is a semicircle (rx = half width); the right button is the top-right
     * quadrant, filled in the accent color and pulsed via CSS to cue the right-click.
     */
    private fun mouseSvg(): String {
        val cur = ThemeManager.current.menuFg.toString()      // mouse outline / wheel
        val accent = ThemeManager.current.link.toString()      // highlighted right button
        return """
            <svg class="tdz-intro-mouse" width="54" height="80" viewBox="0 0 60 90" fill="none">
              <path class="tdz-intro-rbtn" d="M30 6 A24 24 0 0 1 54 30 L54 41 L30 41 Z" fill="$accent"/>
              <rect x="6" y="6" width="48" height="78" rx="24" fill="none" stroke="$cur" stroke-width="3"/>
              <line x1="30" y1="6" x2="30" y2="41" stroke="$cur" stroke-width="2.5"/>
              <line x1="6" y1="41" x2="54" y2="41" stroke="$cur" stroke-width="2.5"/>
              <rect x="27" y="15" width="6" height="12" rx="3" fill="$cur"/>
            </svg>
        """.trimIndent()
    }

    private fun ensureStyles() {
        val t = ThemeManager.current
        // DOS's square cards read fine, but a square welcome dialog reads broken; round it anyway.
        val radius = if (t.cardRadius.toString() == "0px") 14.px else t.cardRadius
        styleSheet("tdz-intro-styles") {
            val enter = keyframes("tdz-intro-in") {
                from { opacity = 0.0; put("transform", "translateY(10px) scale(0.96)") }
                to { opacity = 1.0; put("transform", "none") }
            }
            val rightButtonPulse = keyframes("tdz-intro-rbtn") {
                at(0.pct, 100.pct) { opacity = 0.3 }
                at(50.pct) { opacity = 1.0 }
            }
            val glow = keyframes("tdz-intro-glow") {
                at(0.pct, 100.pct) {
                    put("box-shadow", "0 12px 44px ${t.scrimBg}, 0 0 0 1px ${t.menuBorder}, 0 0 16px -4px ${t.link}")
                }
                at(50.pct) {
                    put("box-shadow", "0 12px 44px ${t.scrimBg}, 0 0 0 1px ${t.link}, 0 0 34px 0px ${t.link}")
                }
            }
            rule("#$ID") {
                position = Position.fixed
                top = 0.px; left = 0.px; right = 0.px; bottom = 0.px
                display = Display.flex
                alignItems = Align.center
                justifyContent = JustifyContent.center
                pointerEvents = PointerEvents.none
                zIndex = ZLayer.WELCOME_CARD
            }
            rule("#$ID .tdz-intro-card") {
                display = Display.flex
                alignItems = Align.center
                gap = 20.px
                textAlign = TextAlign.left
                maxWidth = 460.px
                backgroundColor = t.menuBg
                color = t.menuFg
                border = Border(1.px, BorderStyle.solid, t.menuBorder)
                borderRadius = radius
                padding = Padding(vertical = 24.px, horizontal = 28.px)
                fontFamily = t.fontFamily
                animation(enter, 360.ms, cubicBezier(0.2, 0.8, 0.3, 1.0), fillMode = FillMode.both)
                animation(glow, 2.6.s, Timing.easeInOut, delay = 360.ms, iterationCount = IterationCount.infinite)
            }
            rule("#$ID .tdz-intro-mouse") {
                put("flex", "0 0 auto")
                display = Display.block
            }
            rule("#$ID .tdz-intro-rbtn") {
                animation(rightButtonPulse, 1.2.s, Timing.easeInOut, iterationCount = IterationCount.infinite)
                put("transform-origin", "center")
            }
            rule("#$ID .tdz-intro-text") { minWidth = 0.px }
            rule("#$ID .tdz-intro-h") {
                fontSize = 20.px
                fontWeight = FontWeight.w600
                margin = Margin(0.px)
                marginBottom = 6.px
                letterSpacing = 0.01.em
                fontFamily = t.fontFamily
            }
            rule("#$ID .tdz-intro-p") {
                fontSize = 14.px
                lineHeight = LineHeight("1.55")
                margin = Margin(0.px)
                opacity = 0.92
                fontFamily = t.fontFamily
            }
            rule("#$ID .tdz-intro-accent") {
                color = t.link
                fontWeight = FontWeight.w700
                whiteSpace = WhiteSpace.nowrap
            }
            // The card itself is pointer-events:none (right-clicks pass through to the canvas);
            // the button opts back in as the single clickable affordance.
            rule("#$ID .tdz-intro-btn") {
                pointerEvents = PointerEvents.auto
                cursor = Cursor.pointer
                marginTop = 16.px
                display = Display.inlineBlock
                fontFamily = t.fontFamily
                fontSize = 13.px
                fontWeight = FontWeight.w600
                backgroundColor = t.link
                color = t.menuBg
                borderStyle = BorderStyle.none
                borderRadius = 6.px
                padding = Padding(vertical = 8.px, horizontal = 16.px)
            }
            rule("#$ID .tdz-intro-btn:hover") { put("filter", "brightness(1.08)") }
        }
    }
}
