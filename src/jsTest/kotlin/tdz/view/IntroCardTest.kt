package tdz.view

import tdz.model.Canvas
import tdz.model.Mode
import tdz.model.Model
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.localstorage.flushPendingWrites
import tdz.rootCanvas
import web.dom.document
import web.events.EventType
import web.html.HTMLElement
import web.mouse.MouseEvent
import web.mouse.MouseEventInit
import zoned.framework.events.EventBus
import kotlinx.browser.window
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The empty-root welcome card and its hand-off to the "What is this?" wizard: opening the wizard
 * must dismiss the welcome card (it should never sit behind/over the wizard), and a re-render while
 * the wizard is open must not bring it back.
 */
class IntroCardTest {

    // The root canvas ("_") and the body are shared across the whole suite, so scrub BOTH before and
    // after: a stale root column or a leftover wizard from another test would defeat the empty-root
    // "pristine" check this card depends on.
    @BeforeTest fun reset() = clean()

    @AfterTest fun clean() {
        ExamplesWizard.hide()
        Zones.INTRO.resolve()?.remove()
        flushPendingWrites()        // drain the shared write-behind buffer; clear() alone won't
        window.localStorage.clear()
    }

    private fun pristineRoot(): Pair<Model, Canvas> {
        val model = Model(rootCanvas).also { it.addCanvas(canvasFromTemplate(rootCanvas, null, SINGLE_PANE)) }
        return model to model.getCanvas(rootCanvas)!!
    }

    @Test
    fun welcomeCardShowsOnPristineRoot() {
        val (model, canvas) = pristineRoot()
        IntroCard.evaluate(model, canvas, EventBus()) { Mode.CARD }
        assertNotNull(Zones.INTRO.resolve(), "welcome card shows on an empty root board")
    }

    @Test
    fun clickingWhatIsThisDismissesWelcomeAndOpensWizard() {
        val (model, canvas) = pristineRoot()
        IntroCard.evaluate(model, canvas, EventBus()) { Mode.CARD }
        val btn = document.querySelector(".tdz-intro-btn") as? HTMLElement
        assertNotNull(btn, "the What is this? button is rendered")
        btn.dispatchEvent(MouseEvent(EventType("click"), MouseEventInit(bubbles = true, cancelable = true)))
        assertNull(Zones.INTRO.resolve(), "welcome card is dismissed when the wizard opens")
        assertNotNull(Zones.EXAMPLES_WIZARD.resolve(), "the wizard opens")
    }

    @Test
    fun welcomeStaysHiddenWhileWizardIsOpen() {
        val (model, canvas) = pristineRoot()
        ExamplesWizard.show(model, EventBus())
        IntroCard.evaluate(model, canvas, EventBus()) { Mode.CARD }   // a re-render while the wizard is up
        assertNull(Zones.INTRO.resolve(), "a re-render must not pop the welcome card back over the wizard")
    }

    @Test
    fun switchingTheColumnToEditDismissesTheWelcomeCard() {
        val (model, canvas) = pristineRoot()
        IntroCard.evaluate(model, canvas, EventBus()) { Mode.CARD }
        assertNotNull(Zones.INTRO.resolve(), "shown while viewing the empty card column")

        IntroCard.evaluate(model, canvas, EventBus()) { Mode.EDITOR }   // user switched to edit
        assertNull(Zones.INTRO.resolve(), "dismissed once they're editing the column")

        IntroCard.evaluate(model, canvas, EventBus()) { Mode.CARD }     // saved back, column still empty
        assertNotNull(Zones.INTRO.resolve(), "comes back if they save without editing")
    }
}
