package tdz.view

import kotlinx.css.*
import kotlinx.css.properties.*
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.div
import tdz.controller.events.Event
import tdz.model.Model
import tdz.view.theme.ThemeManager
import tdz.view.theme.themedStyleSheet
import zoned.framework.dom.onClick
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import zoned.framework.interop.animation
import zoned.framework.interop.brightness
import zoned.framework.interop.filter
import zoned.framework.interop.keyframes
import zoned.framework.routing.Zone.Companion.zone

/**
 * The "What is this?" intro wizard, opened from the empty-root welcome card ([IntroCard]). A
 * compact floating panel — deliberately NOT a full-screen modal — so the board stays visible and
 * interactive behind it: picking an example live-rebuilds the board via [ExampleLoader] while the
 * panel stays open, letting the user flip between examples. "Reset board" wipes whatever's loaded.
 *
 * Structured as a list of [WizardStep]s so later steps are a data change; "That's cool, what next?"
 * advances, and closes the tour once it runs out of steps. Only step one — "Columns and cards" —
 * exists today.
 *
 * Self-contained body-level zone ([Zones.EXAMPLES_WIZARD]) built with the typed DSL, mirroring the
 * IntroCard / ContextMenu rendering model.
 */
object ExamplesWizard {

    private val ID get() = Zones.EXAMPLES_WIZARD.elementId

    /**
     * One step of the wizard: a teaching point, optional clickable [examples] (a gallery the user
     * picks from), and an optional [preset] board dropped onto the canvas on arrival (so there's
     * something concrete to look at — no button, it's just there).
     */
    private data class WizardStep(
        val title: String,
        val explanation: String,
        val examples: List<ExampleBoard> = emptyList(),
        val preset: List<ExampleBoard> = emptyList(),
    )

    private val steps = listOf(
        WizardStep(
            title = "Columns and cards",
            explanation = "A column holds cards. Cards can be dragged between columns.",
            examples = ExampleBoards.COLUMNS_AND_CARDS,
        ),
        WizardStep(
            title = "It's all markdown",
            explanation = "Right-click a column and choose Edit — or press Ctrl+' while it's " +
                "focused — to switch to edit mode. Do the same to switch back.",
            preset = listOf(ExampleBoards.PROJECT),
        ),
        WizardStep(
            title = "Privacy, offline first",
            explanation = "This app runs entirely in your browser. Your data is stored here, on " +
                "your device. There is no server — your data is yours, and yours alone.",
        ),
        WizardStep(
            title = "The context menu",
            explanation = "Right-click anywhere — the context menu is your gateway to the app. " +
                "Configure sync, restore a backup, switch themes, learn keyboard shortcuts, and more.",
        ),
        WizardStep(
            title = "Multiple boards",
            explanation = "Of course your life doesn't fit neatly into one screen. Create a link to " +
                "a new board by surrounding a word or phrase in square brackets. Board addresses are " +
                "bookmarkable, too.",
            preset = ExampleBoards.MULTI_BOARD,
        ),
    )

    private var stepIndex = 0

    fun show(model: Model, eventBus: EventBus<Event>) {
        if (Zones.EXAMPLES_WIZARD.resolve() != null) return
        stepIndex = 0
        mount(model, eventBus)
    }

    private fun mount(model: Model, eventBus: EventBus<Event>) {
        ensureStyles()
        val step = steps[stepIndex]
        addToBody {
            div {
                zone(Zones.EXAMPLES_WIZARD)
                div("tdz-wiz-panel") {
                    div("tdz-wiz-top") {
                        div("tdz-wiz-title") { +"What is this?" }
                        button(classes = "tdz-wiz-close", type = ButtonType.button) {
                            +"×"
                            onClick { exit(model, eventBus) }
                        }
                    }
                    // The step indicator only earns its place once there's more than one step.
                    if (steps.size > 1) div("tdz-wiz-progress") { +"Step ${stepIndex + 1} of ${steps.size}" }
                    renderStep(step, model, eventBus)
                    div("tdz-wiz-footer") {
                        // Reset only earns its place on step one, where the user is loading and
                        // swapping example boards; later steps are preset or informational.
                        if (stepIndex == 0) {
                            button(classes = "tdz-wiz-reset", type = ButtonType.button) {
                                +"Reset board"
                                onClick { ExampleLoader.reset(model, eventBus) }
                            }
                        }
                        button(classes = "tdz-wiz-next", type = ButtonType.button) {
                            // The forward button finishes the tour on the final step.
                            +(if (stepIndex == steps.lastIndex) "Got it!" else "That's cool, what next?")
                            onClick { next(model, eventBus) }
                        }
                    }
                }
            }
        }
        // A preset step needs its board(s) already on the canvas to talk about; load now that the
        // panel is mounted (so the panel survives the board's re-render).
        if (step.preset.isNotEmpty()) ExampleLoader.loadScene(model, eventBus, step.preset)
    }

    private fun FlowContent.renderStep(step: WizardStep, model: Model, eventBus: EventBus<Event>) {
        div("tdz-wiz-step-title") { +step.title }
        div("tdz-wiz-explain") { +step.explanation }
        // Informational steps (privacy, the context menu) carry no examples — skip the gallery.
        if (step.examples.isNotEmpty()) {
            div("tdz-wiz-ex-head") { +"Examples" }
            div("tdz-wiz-ex-intro") { +"Click the buttons to see previews." }
            div("tdz-wiz-examples") {
                step.examples.forEach { example ->
                    button(classes = "tdz-wiz-example", type = ButtonType.button) {
                        // Replaces the board in place; the panel stays up so the next click is one away.
                        onClick { ExampleLoader.load(model, eventBus, example) }
                        div("tdz-wiz-ex-title") { +example.title }
                        div("tdz-wiz-ex-tag") { +example.tagline }
                    }
                }
            }
        }
    }

    /** Advance to the next teaching step, or finish the tour when there are no more. */
    private fun next(model: Model, eventBus: EventBus<Event>) {
        if (stepIndex < steps.lastIndex) {
            stepIndex++
            hide()
            mount(model, eventBus)
        } else {
            exit(model, eventBus)
        }
    }

    /** Leave the tour: take the panel down and wipe every example board it created. */
    private fun exit(model: Model, eventBus: EventBus<Event>) {
        hide()
        ExampleLoader.reset(model, eventBus)
    }

    /** Take the panel down (no cleanup) — used for step transitions and by tests. */
    fun hide() { Zones.EXAMPLES_WIZARD.resolve()?.remove() }

    private fun ensureStyles() {
        val t = ThemeManager.current
        // The panel's resting elevation: a soft drop plus a crisp accent ring so it reads as the
        // one thing to look at, over a quiet board.
        val drop = BoxShadow(t.scrimBg, 0.px, 16.px, 50.px, 0.px)
        val ring = BoxShadow(t.link, 0.px, 0.px, 0.px, 1.px)
        themedStyleSheet("$ID-styles") {
            val enter = keyframes("tdz-wiz-in") {
                from { opacity = 0.0; transform { translateX(14.px); scale(0.98) } }
                to { opacity = 1.0; transform = Transforms.none }
            }
            // A few attention pulses on arrival, then settle to the resting shadow.
            val glow = keyframes("tdz-wiz-glow") {
                at(0.pct, 100.pct) { boxShadow += drop; boxShadow += ring }
                at(50.pct) {
                    boxShadow += drop; boxShadow += ring
                    boxShadow += BoxShadow(t.link, 0.px, 0.px, 26.px, 0.px)
                }
            }
            // Full-viewport wrapper that lets clicks fall through to the board; only the panel captures.
            rule("#$ID") {
                position = Position.fixed
                top = 0.px; left = 0.px; right = 0.px; bottom = 0.px
                pointerEvents = PointerEvents.none
                zIndex = ZLayer.EXAMPLES_WIZARD
                fontFamily = t.fontFamily
            }
            rule("#$ID .tdz-wiz-panel") {
                position = Position.absolute
                top = 16.px
                right = 16.px
                width = 330.px
                maxWidth = 88.pct
                pointerEvents = PointerEvents.auto
                backgroundColor = t.menuBg
                color = t.menuFg
                border = Border(1.px, BorderStyle.solid, t.link)
                borderRadius = t.dialogRadius
                padding = Padding(vertical = 18.px, horizontal = 20.px)
                boxShadow += drop; boxShadow += ring
                animation(enter, 280.ms, cubicBezier(0.2, 0.8, 0.3, 1.0), fillMode = FillMode.both)
                animation(glow, 1.3.s, Timing.easeInOut, delay = 280.ms, iterationCount = IterationCount("3"))
            }
            rule("#$ID .tdz-wiz-top") {
                display = Display.flex
                alignItems = Align.center
                justifyContent = JustifyContent.spaceBetween
                marginBottom = 4.px
            }
            rule("#$ID .tdz-wiz-title") {
                fontSize = 12.px
                fontWeight = FontWeight.w700
                letterSpacing = 0.06.em
                textTransform = TextTransform.uppercase
                color = t.link
            }
            rule("#$ID .tdz-wiz-close") {
                cursor = Cursor.pointer
                backgroundColor = Color.transparent
                color = t.menuFg
                borderStyle = BorderStyle.none
                fontSize = 20.px
                lineHeight = LineHeight("1")
                padding = Padding(0.px, 4.px)
                opacity = 0.6
            }
            rule("#$ID .tdz-wiz-close:hover") { opacity = 1.0 }
            rule("#$ID .tdz-wiz-progress") {
                fontSize = 12.px
                opacity = 0.6
                marginBottom = 4.px
            }
            rule("#$ID .tdz-wiz-step-title") {
                fontSize = 19.px
                fontWeight = FontWeight.w600
                marginTop = 6.px
            }
            rule("#$ID .tdz-wiz-explain") {
                fontSize = 13.5.px
                lineHeight = LineHeight("1.5")
                opacity = 0.9
                marginTop = 6.px
                marginBottom = 16.px
            }
            rule("#$ID .tdz-wiz-ex-head") {
                fontSize = 12.px
                fontWeight = FontWeight.w700
                letterSpacing = 0.05.em
                textTransform = TextTransform.uppercase
                opacity = 0.7
            }
            rule("#$ID .tdz-wiz-ex-intro") {
                fontSize = 12.5.px
                opacity = 0.8
                marginTop = 3.px
                marginBottom = 10.px
            }
            rule("#$ID .tdz-wiz-examples") {
                display = Display.flex
                flexDirection = FlexDirection.column
                gap = 8.px
            }
            // Each example is a single click that rebuilds the board.
            rule("#$ID .tdz-wiz-example") {
                cursor = Cursor.pointer
                textAlign = TextAlign.left
                width = 100.pct
                backgroundColor = t.scrimBg
                color = t.menuFg
                border = Border(1.px, BorderStyle.solid, t.menuBorder)
                borderRadius = 8.px
                padding = Padding(vertical = 10.px, horizontal = 12.px)
                fontFamily = t.fontFamily
                transition += Transition("border-color", 120.ms, Timing.easeOut)
                transition += Transition("background-color", 120.ms, Timing.easeOut)
            }
            rule("#$ID .tdz-wiz-example:hover") {
                borderColor = t.link
                filter(brightness(1.06))
            }
            rule("#$ID .tdz-wiz-ex-title") {
                fontSize = 14.px
                fontWeight = FontWeight.w600
                color = t.link
            }
            rule("#$ID .tdz-wiz-ex-tag") {
                fontSize = 12.5.px
                opacity = 0.8
                marginTop = 2.px
            }
            rule("#$ID .tdz-wiz-footer") {
                display = Display.flex
                alignItems = Align.center
                justifyContent = JustifyContent.spaceBetween
                gap = 10.px
                marginTop = 16.px
            }
            // A quiet text link — destructive-ish, so it stays understated next to the primary CTA.
            rule("#$ID .tdz-wiz-reset") {
                cursor = Cursor.pointer
                backgroundColor = Color.transparent
                color = t.menuFg
                borderStyle = BorderStyle.none
                padding = Padding(0.px)
                fontSize = 12.5.px
                fontFamily = t.fontFamily
                opacity = 0.6
                textDecoration = TextDecoration(setOf(TextDecorationLine.underline))
            }
            rule("#$ID .tdz-wiz-reset:hover") { opacity = 1.0 }
            rule("#$ID .tdz-wiz-next") {
                marginLeft = LinearDimension("auto")   // stay right-aligned even when Reset is absent
                cursor = Cursor.pointer
                fontSize = 13.px
                fontWeight = FontWeight.w600
                backgroundColor = t.link
                color = t.menuBg
                borderStyle = BorderStyle.none
                borderRadius = 6.px
                padding = Padding(vertical = 8.px, horizontal = 14.px)
                fontFamily = t.fontFamily
            }
            rule("#$ID .tdz-wiz-next:hover") { filter(brightness(1.08)) }
        }
    }
}
