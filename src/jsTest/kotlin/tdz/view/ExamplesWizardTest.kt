package tdz.view

import tdz.model.CanvasName
import tdz.model.Model
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.rootCanvas
import web.dom.document
import web.events.EventType
import web.html.HTMLElement
import web.mouse.MouseEvent
import web.mouse.MouseEventInit
import zoned.framework.events.EventBus
import kotlinx.browser.window
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The intro wizard ("What is this?") opens on a welcome card, then walks the feature steps. Step one
 * ("Columns and cards") renders a button per curated example, the tour closes on demand, and picking
 * an example rebuilds the board in place.
 */
class ExamplesWizardTest {

    @AfterTest fun clean() { ExamplesWizard.hide(); window.localStorage.clear() }

    private fun rootModel() =
        Model(rootCanvas).also { it.addCanvas(canvasFromTemplate(rootCanvas, null, SINGLE_PANE)) }

    private fun clickNext() =
        (document.querySelector(".tdz-wiz-next") as HTMLElement)
            .dispatchEvent(MouseEvent(EventType("click"), MouseEventInit(bubbles = true, cancelable = true)))

    /** Show the wizard and advance past the welcome card to the first feature step. */
    private fun showAtFeatureOne(model: Model) {
        ExamplesWizard.show(model, EventBus())
        clickNext()
    }

    @Test
    fun showRendersTheWelcomeStepFirst() {
        ExamplesWizard.show(rootModel(), EventBus())
        val body = document.body.textContent ?: ""
        assertTrue(body.contains("Welcome to The Do Zone"), "welcome title; got '$body'")
        assertTrue(body.contains("5 quick steps"), "welcome blurb names the step count; got '$body'")
        assertTrue(body.contains("Let's go!"), "welcome CTA; got '$body'")
        assertNull(document.querySelector(".tdz-wiz-example"), "welcome carries no example buttons")
        assertNull(document.querySelector(".tdz-wiz-reset"), "welcome carries no reset link")
    }

    @Test
    fun showRendersStepOneWithEveryExample() {
        showAtFeatureOne(rootModel())
        val body = document.body.textContent ?: ""
        assertTrue(body.contains("Columns and cards"), "step title; got '$body'")
        assertTrue(body.contains("Step 1 of 5"), "progress counts feature steps only; got '$body'")
        assertTrue(body.contains("dragged between columns"), "explanation; got '$body'")
        assertTrue(body.contains("Examples"), "examples heading; got '$body'")
        assertTrue(body.contains("Click the buttons to see previews"), "examples intro; got '$body'")
        ExampleBoards.COLUMNS_AND_CARDS.forEach {
            assertTrue(body.contains(it.title), "shows the '${it.title}' button; got '$body'")
        }
    }

    @Test
    fun nextAdvancesToTheEditingStepAndPresetsItsExample() {
        val model = rootModel()
        showAtFeatureOne(model)
        val next = document.querySelector(".tdz-wiz-next") as? HTMLElement
        assertNotNull(next, "the next button is present")
        next.dispatchEvent(MouseEvent(EventType("click"), MouseEventInit(bubbles = true, cancelable = true)))

        val body = document.body.textContent ?: ""
        assertTrue(body.contains("It's all markdown"), "step two title; got '$body'")
        assertTrue(body.contains("Edit"), "the editing instruction; got '$body'")
        assertNull(document.querySelector(".tdz-wiz-example"), "step two is preset, no example buttons")

        val project = ExampleBoards.PROJECT
        val canvas = model.getCanvas(rootCanvas)!!
        assertEquals(
            project.contentColumns().toSet(),
            canvas.columns.filterNot { canvas.layoutMap.containsKey(it) }.toSet(),
            "the editing example is preset onto the board",
        )
    }

    @Test
    fun closeButtonRemovesTheWizard() {
        ExamplesWizard.show(rootModel(), EventBus())
        assertNotNull(Zones.EXAMPLES_WIZARD.resolve(), "wizard is mounted")
        val close = document.querySelector(".tdz-wiz-close") as? HTMLElement
        assertNotNull(close, "close button is rendered")
        close.dispatchEvent(MouseEvent(EventType("click"), MouseEventInit(bubbles = true, cancelable = true)))
        assertNull(Zones.EXAMPLES_WIZARD.resolve(), "close button removes the wizard")
    }

    @Test
    fun footerOffersResetAndNext() {
        showAtFeatureOne(rootModel())
        val body = document.body.textContent ?: ""
        assertTrue(body.contains("Reset board"), "reset link; got '$body'")
        assertTrue(body.contains("That's cool, what next?"), "next button; got '$body'")
    }

    @Test
    fun resetLinkWipesTheExampleFromTheBoard() {
        val model = rootModel()
        showAtFeatureOne(model)
        ExampleLoader.load(model, EventBus(), ExampleBoards.ALL.first { it.title.contains("Week") })

        val reset = document.querySelector(".tdz-wiz-reset") as? HTMLElement
        assertNotNull(reset, "reset link is rendered")
        reset.dispatchEvent(MouseEvent(EventType("click"), MouseEventInit(bubbles = true, cancelable = true)))

        val canvas = model.getCanvas(rootCanvas)!!
        assertEquals(1, canvas.columns.filterNot { canvas.layoutMap.containsKey(it) }.size,
            "the board is back to a single empty column")
    }

    @Test
    fun theTourAdvancesThroughEveryStepThenCloses() {
        val model = rootModel()
        ExamplesWizard.show(model, EventBus())

        assertTrue((document.body.textContent ?: "").contains("Welcome to The Do Zone"), "welcome")
        clickNext()
        assertTrue((document.body.textContent ?: "").contains("Columns and cards"), "step 1")
        clickNext()
        assertTrue((document.body.textContent ?: "").contains("It's all markdown"), "step 2")
        clickNext()
        val privacy = document.body.textContent ?: ""
        assertTrue(privacy.contains("Privacy, offline first"), "step 3")
        assertNull(document.querySelector(".tdz-wiz-example"), "informational steps carry no example buttons")
        clickNext()
        assertTrue((document.body.textContent ?: "").contains("context menu"), "step 4")
        clickNext()
        assertTrue((document.body.textContent ?: "").contains("Multiple boards"), "step 5")
        clickNext()
        assertNull(Zones.EXAMPLES_WIZARD.resolve(), "the final step finishes the tour")
    }

    @Test
    fun exitingTheTourWipesEveryExampleBoard() {
        val model = rootModel()
        ExamplesWizard.show(model, EventBus())
        // Advance from the welcome card to the final "Multiple boards" step, which presets the root
        // plus child boards (welcome + 4 nexts = the 5th and last feature step).
        repeat(5) {
            (document.querySelector(".tdz-wiz-next") as HTMLElement)
                .dispatchEvent(MouseEvent(EventType("click"), MouseEventInit(bubbles = true, cancelable = true)))
        }
        assertNotNull(model.getCanvas(CanvasName("Work")), "a child board exists during the tour")

        (document.querySelector(".tdz-wiz-close") as HTMLElement)
            .dispatchEvent(MouseEvent(EventType("click"), MouseEventInit(bubbles = true, cancelable = true)))

        assertNull(Zones.EXAMPLES_WIZARD.resolve(), "the wizard closed")
        assertNull(model.getCanvas(CanvasName("Work")), "example child boards are wiped on exit")
    }

    @Test
    fun clickingAnExampleRebuildsTheBoardInPlace() {
        val model = rootModel()
        showAtFeatureOne(model)

        val list = document.querySelectorAll(".tdz-wiz-example")
        var button: HTMLElement? = null
        for (i in 0 until list.length) {
            val el = list.item(i) as? HTMLElement ?: continue
            if (el.textContent?.contains("Week planner") == true) { button = el; break }
        }
        assertNotNull(button, "the Week planner example button is present")
        button.dispatchEvent(MouseEvent(EventType("click"), MouseEventInit(bubbles = true, cancelable = true)))

        val canvas = model.getCanvas(rootCanvas)!!
        assertEquals(5, canvas.columns.filterNot { canvas.layoutMap.containsKey(it) }.size,
            "the board became the 5-column week planner")
    }
}
