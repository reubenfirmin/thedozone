package tdz.view

import kotlinx.html.ButtonType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.span
import tdz.controller.events.Event
import tdz.model.Canvas
import tdz.model.ColumnId
import tdz.model.Mode
import tdz.model.Model
import tdz.rootCanvas
import zoned.framework.events.EventBus
import zoned.framework.tags.svg
import kotlinx.css.*
import kotlinx.css.properties.*
import tdz.view.theme.ThemeManager
import zoned.framework.dom.onClick
import zoned.framework.interop.addToBody
import zoned.framework.interop.brightness
import zoned.framework.interop.filter
import zoned.framework.interop.transformOrigin
import zoned.framework.interop.animation
import zoned.framework.interop.keyframes
import tdz.view.theme.themedStyleSheet
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
    fun evaluate(model: Model, canvas: Canvas, eventBus: EventBus<Event>, modeOf: (ColumnId) -> Mode) {
        // The wizard owns the screen once it's open; never let the welcome card sit behind it (a
        // re-render mid-tour must not pop it back).
        if (Zones.EXAMPLES_WIZARD.resolve() != null) { remove(); return }
        val leaves = canvas.columns.filter { !canvas.layoutMap.containsKey(it) }
        // The card is for an untouched root: one empty column the user hasn't started editing.
        // Switching that column into the editor means they're working — get out of the way (saving
        // back to an empty card column brings it back).
        val pristine = canvas.name == rootCanvas &&
            leaves.size == 1 && model.cards(canvas.name, leaves[0]).isEmpty() &&
            modeOf(leaves[0]) == Mode.CARD
        if (pristine) show(model, eventBus) else remove()
    }

    private var hintHooked = false

    private fun show(model: Model, eventBus: EventBus<Event>) {
        ensureStyles()                 // refresh each time so a theme switch while empty recolors the card
        PointerHint.start()            // begin sniffing wheel events to refine the instruction
        hookPointerHint()
        if (Zones.INTRO.resolve() != null) return
        addToBody {
            div {
                zone(Zones.INTRO)
                div("tdz-intro-card") {
                    // A right-click cue: a mouse with the right button accented + CSS-pulsed (the
                    // .tdz-intro-rbtn class). Built with the typed SVG DSL (namespaced via
                    // createElementNS), so there's no raw-HTML fragment anywhere in the app.
                    val cur = ThemeManager.current.menuFg.toString()      // mouse outline / wheel
                    val accent = ThemeManager.current.link.toString()     // highlighted right button
                    svg("tdz-intro-mouse") {
                        width = "54"; height = "80"; viewBox = "0 0 60 90"; fill = "none"
                        g {
                            path {
                                attributes["class"] = "tdz-intro-rbtn"
                                d = "M30 6 A24 24 0 0 1 54 30 L54 41 L30 41 Z"; fill = accent
                            }
                            rect { x = "6"; y = "6"; width = "48"; height = "78"; rx = "24"; fill = "none"; stroke = cur; strokeWidth = "3" }
                            line { x1 = "30"; y1 = "6"; x2 = "30"; y2 = "41"; stroke = cur; strokeWidth = "2.5" }
                            line { x1 = "6"; y1 = "41"; x2 = "54"; y2 = "41"; stroke = cur; strokeWidth = "2.5" }
                            rect { x = "27"; y = "15"; width = "6"; height = "12"; rx = "3"; fill = cur }
                        }
                    }
                    div("tdz-intro-text") {
                        div("tdz-intro-h") { +"Welcome to The Do Zone" }
                        div("tdz-intro-p") {
                            span("tdz-intro-accent") { +instruction() }
                            +" anywhere to open the menu and get started."
                        }
                        // The card itself is click-through (pointer-events: none); the button opts
                        // back in (see CSS) so it's the one clickable affordance. Opens the
                        // intro wizard.
                        button(classes = "tdz-intro-btn", type = ButtonType.button) {
                            +"What is this?"
                            // Hand off to the wizard and step aside so it isn't left sitting behind it.
                            onClick { remove(); ExamplesWizard.show(model, eventBus) }
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

    private fun ensureStyles() {
        val t = ThemeManager.current
        val radius = t.dialogRadius
        themedStyleSheet("tdz-intro-styles") {
            val enter = keyframes("tdz-intro-in") {
                from { opacity = 0.0; transform { translateY(10.px); scale(0.96) } }
                to { opacity = 1.0; transform = Transforms.none }
            }
            val rightButtonPulse = keyframes("tdz-intro-rbtn") {
                at(0.pct, 100.pct) { opacity = 0.3 }
                at(50.pct) { opacity = 1.0 }
            }
            val glow = keyframes("tdz-intro-glow") {
                at(0.pct, 100.pct) {
                    boxShadow += BoxShadow(t.scrimBg, 0.px, 12.px, 44.px, 0.px)
                    boxShadow += BoxShadow(t.menuBorder, 0.px, 0.px, 0.px, 1.px)
                    boxShadow += BoxShadow(t.link, 0.px, 0.px, 16.px, (-4).px)
                }
                at(50.pct) {
                    boxShadow += BoxShadow(t.scrimBg, 0.px, 12.px, 44.px, 0.px)
                    boxShadow += BoxShadow(t.link, 0.px, 0.px, 0.px, 1.px)
                    boxShadow += BoxShadow(t.link, 0.px, 0.px, 34.px, 0.px)
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
                flexGrow = 0.0
                flexShrink = 0.0
                flexBasis = FlexBasis.auto
                display = Display.block
            }
            rule("#$ID .tdz-intro-rbtn") {
                animation(rightButtonPulse, 1.2.s, Timing.easeInOut, iterationCount = IterationCount.infinite)
                transformOrigin("center")
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
            rule("#$ID .tdz-intro-btn:hover") { filter(brightness(1.08)) }
        }
    }
}
