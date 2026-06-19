package tdz.model

import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelWormholeTest {

    @Test
    fun movingACanvasLinkCardDoesNotReparentTheLinkedCanvas() {
        val model = Model(CanvasName("_wh-a"))
        val a = CanvasName("_wh-a"); val b = CanvasName("_wh-b"); val c = CanvasName("_wh-c")
        model.addCanvas(canvasFromTemplate(a, null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(b, null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(c, a, SINGLE_PANE))
        model.updateColumn(a, ColumnId("1"), "[${c.normalized()} (child)]\n")
        val linkCard = model.cards(a, ColumnId("1")).first()
        assertTrue(linkCard.isCanvasLink(), "setup: the card parses as a canvas link")

        model.moveCardToCanvas(linkCard, fromCanvas = a, toCanvas = b)

        assertEquals(a, model.getCanvas(c)?.parent, "moving a link card must NOT reparent the linked canvas")
    }
}
