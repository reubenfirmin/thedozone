package tdz.view

import kotlinx.html.ButtonType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.span
import kotlinx.html.unsafe
import tdz.model.Canvas
import tdz.model.Model
import tdz.rootCanvas
import tdz.view.theme.ThemeManager
import zoned.framework.dom.onClick
import zoned.framework.interop.addToBody
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
    private const val SANS = "ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"

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
        val bg = t.menuBg.toString()
        val fg = t.menuFg.toString()
        val border = t.menuBorder.toString()
        val link = t.link.toString()
        val radius = t.cardRadius.toString().let { if (it == "0px") "14px" else it }
        styleSheet("tdz-intro-styles") {
            raw("""
                #$ID { position: fixed; inset: 0; display: flex; align-items: center; justify-content: center;
                       pointer-events: none; z-index: 500; }
                #$ID .tdz-intro-card { display: flex; align-items: center; gap: 20px; text-align: left;
                       max-width: 460px; background: $bg; color: $fg; border: 1px solid $border;
                       border-radius: $radius; padding: 24px 28px; font-family: $SANS;
                       animation: tdz-intro-in 360ms cubic-bezier(0.2,0.8,0.3,1) both,
                                  tdz-intro-glow 2.6s ease-in-out infinite 360ms; }
                #$ID .tdz-intro-mouse { flex: 0 0 auto; display: block; }
                #$ID .tdz-intro-rbtn { animation: tdz-intro-rbtn 1.2s ease-in-out infinite; transform-origin: center; }
                #$ID .tdz-intro-text { min-width: 0; }
                #$ID .tdz-intro-h { font-size: 20px; font-weight: 600; margin: 0 0 6px; letter-spacing: 0.01em;
                       font-family: $SANS; }
                #$ID .tdz-intro-p { font-size: 14px; line-height: 1.55; margin: 0; opacity: 0.92; font-family: $SANS; }
                #$ID .tdz-intro-accent { color: $link; font-weight: 700; white-space: nowrap; }
                #$ID .tdz-intro-btn { pointer-events: auto; cursor: pointer; margin-top: 16px; display: inline-block;
                       font-family: $SANS; font-size: 13px; font-weight: 600; background: $link; color: $bg;
                       border: none; border-radius: 6px; padding: 8px 16px; }
                #$ID .tdz-intro-btn:hover { filter: brightness(1.08); }
                @keyframes tdz-intro-in { from { opacity: 0; transform: translateY(10px) scale(0.96); }
                                          to { opacity: 1; transform: none; } }
                @keyframes tdz-intro-rbtn { 0%,100% { opacity: 0.3; } 50% { opacity: 1; } }
                @keyframes tdz-intro-glow {
                    0%,100% { box-shadow: 0 12px 44px rgba(0,0,0,0.45), 0 0 0 1px $border, 0 0 16px -4px $link; }
                    50%     { box-shadow: 0 12px 44px rgba(0,0,0,0.45), 0 0 0 1px $link,   0 0 34px 0px $link; } }
            """.trimIndent())
        }
    }
}
