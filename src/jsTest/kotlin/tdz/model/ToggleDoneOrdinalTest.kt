package tdz.model

import tdz.model.Card.Companion.taskItems
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Checkbox identity is POSITIONAL (the ordinal in [Card.Companion.taskItems], i.e. render order) —
 * never structural equality. Two tasks with identical text are equal data classes, so an
 * equality-based lookup flips them all: checking one of two "15 mins - ISO" rows checked the other
 * too, and a later Clear Done then removed items the user never touched.
 */
class ToggleDoneOrdinalTest {

    private fun setup(name: String, md: String): Pair<Model, CanvasName> {
        val canvas = CanvasName(name)
        val model = Model(canvas)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))   // clearDone scans the canvas
        model.updateColumn(canvas, model.getCanvas(canvas)!!.firstNonLayout(), md)
        return model to canvas
    }

    @Test
    fun togglingOneOfTwoIdenticalItemsFlipsOnlyThatOne() {
        val (model, canvas) = setup("_tdo-dup", "# T\n* 15 mins - ISO\n* 15 mins - ISO\n")
        val card = model.cards(canvas, model.getCanvas(canvas)!!.firstNonLayout()).first()

        model.toggleDone(canvas, card, ordinal = 1)

        val items = model.cards(canvas, model.getCanvas(canvas)!!.firstNonLayout()).first().taskItems()
        assertFalse(items[0].done, "the untouched twin must stay unchecked")
        assertTrue(items[1].done, "the clicked row must toggle")
    }

    @Test
    fun ordinalCountsDepthFirstAcrossNesting() {
        val (model, canvas) = setup("_tdo-nest", "# T\n* same\n  * same\n* same\n")
        val card = model.cards(canvas, model.getCanvas(canvas)!!.firstNonLayout()).first()

        model.toggleDone(canvas, card, ordinal = 2)   // the THIRD row in render order

        assertEquals(listOf(false, false, true),
            model.cards(canvas, model.getCanvas(canvas)!!.firstNonLayout()).first().taskItems().map { it.done },
            "the ordinal indexes taskItems() — depth-first, exactly like the rendered rows")
    }

    /** The Clear Done fallout of equality matching: items the user never clicked must survive. */
    @Test
    fun clearDoneAfterTogglingOneTwinKeepsTheOther() {
        val (model, canvas) = setup("_tdo-clear", "# T\n* same\n* same\n")
        val card = model.cards(canvas, model.getCanvas(canvas)!!.firstNonLayout()).first()
        model.toggleDone(canvas, card, ordinal = 0)

        model.clearDone(canvas)

        val items = model.cards(canvas, model.getCanvas(canvas)!!.firstNonLayout()).first().taskItems()
        assertEquals(1, items.size, "only the CHECKED twin is cleared")
        assertFalse(items[0].done)
    }
}
