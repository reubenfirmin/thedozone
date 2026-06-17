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
 * Structured as a list of [WizardStep]s so steps are a data change; the first is a welcome card and
 * the rest ([featureSteps]) walk through one feature each. "That's cool, what next?" advances, and
 * the tour closes once it runs out of steps. The welcome card sits outside the "Step X of N" count,
 * so the progress reflects only the feature steps.
 *
 * Self-contained body-level zone ([Zones.EXAMPLES_WIZARD]) built with the typed DSL, mirroring the
 * IntroCard / ContextMenu rendering model.
 */
object ExamplesWizard {

    private val ID get() = Zones.EXAMPLES_WIZARD.elementId

    // Component-private CSS class names, shared between the markup builders and the stylesheet
    // selectors below so a rename can't silently desync the two. (The keyframes names live inline —
    // they're declared once and referenced by handle, not by literal.)
    private const val PANEL_CLASS = "tdz-wiz-panel"
    private const val TOP_CLASS = "tdz-wiz-top"
    private const val TITLE_CLASS = "tdz-wiz-title"
    private const val CLOSE_CLASS = "tdz-wiz-close"
    private const val PROGRESS_CLASS = "tdz-wiz-progress"
    private const val STEP_TITLE_CLASS = "tdz-wiz-step-title"
    private const val EXPLAIN_CLASS = "tdz-wiz-explain"
    private const val EX_HEAD_CLASS = "tdz-wiz-ex-head"
    private const val EX_INTRO_CLASS = "tdz-wiz-ex-intro"
    private const val EXAMPLES_CLASS = "tdz-wiz-examples"
    private const val EXAMPLE_CLASS = "tdz-wiz-example"
    private const val EX_TITLE_CLASS = "tdz-wiz-ex-title"
    private const val EX_TAG_CLASS = "tdz-wiz-ex-tag"
    private const val FOOTER_CLASS = "tdz-wiz-footer"
    private const val RESET_CLASS = "tdz-wiz-reset"
    private const val NEXT_CLASS = "tdz-wiz-next"

    /**
     * One step of the wizard: a teaching point, optional clickable [examples] (a gallery the user
     * picks from), and an optional [preset] board dropped onto the canvas on arrival (so there's
     * something concrete to look at — no button, it's just there).
     */
    private data class WizardStep(
        val title: String,
        val explanation: String,
        // The forward button's label; varies per step so the tour doesn't feel like one repeated
        // click. The last step's [cta] doubles as the finish label, since the button ends the tour.
        val cta: String,
        val examples: List<ExampleBoard> = emptyList(),
        val preset: List<ExampleBoard> = emptyList(),
    )

    // The feature walk-through; the welcome card is prepended below and excluded from the count, so
    // the "5 quick steps" copy and the "Step X of N" indicator both track this list's size.
    private val featureSteps = listOf(
        WizardStep(
            title = "Columns and cards",
            explanation = "A column holds cards. Cards can be dragged between columns. You can " +
                "organize your board in many different ways. Here are some examples.",
            cta = "That's cool, what next?",
            examples = ExampleBoards.COLUMNS_AND_CARDS,
        ),
        WizardStep(
            title = "It's all markdown",
            explanation = "Every card is just markdown. This board has one of each kind: tasks, " +
                "callouts, a quote, code, a photo, a map, a diagram, even a flip card. Right-click a " +
                "column and choose Edit (or press Ctrl+') to play with the raw markdown, then switch back.",
            cta = "Nice. Show me more",
            preset = listOf(ExampleBoards.MARKDOWN_SHOWCASE),
        ),
        WizardStep(
            title = "Privacy, offline first",
            explanation = "This app runs entirely in your browser. Your data is stored here, on " +
                "your device. There is no server. Your data is yours, and yours alone.",
            cta = "Love it. Keep going",
        ),
        WizardStep(
            title = "The context menu",
            explanation = "Right-click anywhere: the context menu is your gateway to the app. " +
                "Configure sync, restore a backup, switch themes, learn keyboard shortcuts, and more.",
            cta = "Handy. One more?",
        ),
        WizardStep(
            title = "Multiple boards",
            explanation = "Of course your life doesn't fit neatly into one screen. Create a link to " +
                "a new board by surrounding a word or phrase in square brackets. Board addresses are " +
                "bookmarkable, too.",
            cta = "Got it!",
            preset = ExampleBoards.MULTI_BOARD,
        ),
    )

    private val steps = listOf(
        WizardStep(
            title = "Welcome to The Do Zone",
            explanation = "We're going to walk through the main features in ${featureSteps.size} " +
                "quick steps.",
            cta = "Let's go!",
        ),
    ) + featureSteps

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
                div(PANEL_CLASS) {
                    div(TOP_CLASS) {
                        div(TITLE_CLASS) { +"What is this?" }
                        button(classes = CLOSE_CLASS, type = ButtonType.button) {
                            +"×"
                            onClick { exit(model, eventBus) }
                        }
                    }
                    // The step indicator counts feature steps only; the welcome card (index 0) sits
                    // outside the count, so it shows no progress line.
                    if (stepIndex > 0) div(PROGRESS_CLASS) { +"Step $stepIndex of ${featureSteps.size}" }
                    renderStep(step, model, eventBus)
                    div(FOOTER_CLASS) {
                        // Reset only earns its place on the step that loads and swaps example
                        // boards; the welcome card and later steps are preset or informational.
                        if (step.examples.isNotEmpty()) {
                            button(classes = RESET_CLASS, type = ButtonType.button) {
                                +"Reset board"
                                onClick { ExampleLoader.reset(model, eventBus) }
                            }
                        }
                        button(classes = NEXT_CLASS, type = ButtonType.button) {
                            // Each step carries its own forward label; the last step's doubles as the
                            // finish label, since the button ends the tour there.
                            +step.cta
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
        div(STEP_TITLE_CLASS) { +step.title }
        div(EXPLAIN_CLASS) { +step.explanation }
        // Informational steps (privacy, the context menu) carry no examples — skip the gallery.
        if (step.examples.isNotEmpty()) {
            div(EX_HEAD_CLASS) { +"Examples" }
            div(EX_INTRO_CLASS) { +"Click the buttons to see previews." }
            div(EXAMPLES_CLASS) {
                step.examples.forEach { example ->
                    button(classes = EXAMPLE_CLASS, type = ButtonType.button) {
                        // Replaces the board in place; the panel stays up so the next click is one away.
                        onClick { ExampleLoader.load(model, eventBus, example) }
                        div(EX_TITLE_CLASS) { +example.title }
                        div(EX_TAG_CLASS) { +example.tagline }
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
            rule("#$ID .$PANEL_CLASS") {
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
            rule("#$ID .$TOP_CLASS") {
                display = Display.flex
                alignItems = Align.center
                justifyContent = JustifyContent.spaceBetween
                marginBottom = 4.px
            }
            rule("#$ID .$TITLE_CLASS") {
                fontSize = 12.px
                fontWeight = FontWeight.w700
                letterSpacing = 0.06.em
                textTransform = TextTransform.uppercase
                color = t.link
            }
            rule("#$ID .$CLOSE_CLASS") {
                cursor = Cursor.pointer
                backgroundColor = Color.transparent
                color = t.menuFg
                borderStyle = BorderStyle.none
                fontSize = 20.px
                lineHeight = LineHeight("1")
                padding = Padding(0.px, 4.px)
                opacity = 0.6
            }
            rule("#$ID .$CLOSE_CLASS:hover") { opacity = 1.0 }
            rule("#$ID .$PROGRESS_CLASS") {
                fontSize = 12.px
                opacity = 0.6
                marginBottom = 4.px
            }
            rule("#$ID .$STEP_TITLE_CLASS") {
                fontSize = 19.px
                fontWeight = FontWeight.w600
                marginTop = 6.px
            }
            rule("#$ID .$EXPLAIN_CLASS") {
                fontSize = 13.5.px
                lineHeight = LineHeight("1.5")
                opacity = 0.9
                marginTop = 6.px
                marginBottom = 16.px
            }
            rule("#$ID .$EX_HEAD_CLASS") {
                fontSize = 12.px
                fontWeight = FontWeight.w700
                letterSpacing = 0.05.em
                textTransform = TextTransform.uppercase
                opacity = 0.7
            }
            rule("#$ID .$EX_INTRO_CLASS") {
                fontSize = 12.5.px
                opacity = 0.8
                marginTop = 3.px
                marginBottom = 10.px
            }
            rule("#$ID .$EXAMPLES_CLASS") {
                display = Display.flex
                flexDirection = FlexDirection.column
                gap = 8.px
            }
            // Each example is a single click that rebuilds the board.
            rule("#$ID .$EXAMPLE_CLASS") {
                cursor = Cursor.pointer
                textAlign = TextAlign.left
                width = 100.pct
                // menuHover is the theme's subtle on-panel tint (a light wash on dark panels, a dark
                // wash on light ones), so the button reads as a distinct surface under menuFg text on
                // BOTH — scrimBg was a dark overlay that became black-on-gray in light themes.
                backgroundColor = t.menuHover
                color = t.menuFg
                border = Border(1.px, BorderStyle.solid, t.menuBorder)
                borderRadius = 8.px
                padding = Padding(vertical = 10.px, horizontal = 12.px)
                fontFamily = t.fontFamily
                transition += Transition("border-color", 120.ms, Timing.easeOut)
                transition += Transition("background-color", 120.ms, Timing.easeOut)
            }
            rule("#$ID .$EXAMPLE_CLASS:hover") {
                borderColor = t.link
                filter(brightness(1.06))
            }
            rule("#$ID .$EX_TITLE_CLASS") {
                fontSize = 14.px
                fontWeight = FontWeight.w600
                color = t.link
            }
            rule("#$ID .$EX_TAG_CLASS") {
                fontSize = 12.5.px
                opacity = 0.8
                marginTop = 2.px
            }
            rule("#$ID .$FOOTER_CLASS") {
                display = Display.flex
                alignItems = Align.center
                justifyContent = JustifyContent.spaceBetween
                gap = 10.px
                marginTop = 16.px
            }
            // A quiet text link — destructive-ish, so it stays understated next to the primary CTA.
            rule("#$ID .$RESET_CLASS") {
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
            rule("#$ID .$RESET_CLASS:hover") { opacity = 1.0 }
            rule("#$ID .$NEXT_CLASS") {
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
            rule("#$ID .$NEXT_CLASS:hover") { filter(brightness(1.08)) }
        }
    }
}
