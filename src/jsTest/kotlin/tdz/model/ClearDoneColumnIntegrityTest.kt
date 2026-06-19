package tdz.model

import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Clear Done must only ever remove DONE items — every unchecked item in the column survives, no
 * matter how cards shift. The data-loss shape this pins down: clearDone snapshotted all cards and
 * replace()d each at its ORIGINAL index, but a card that was nothing but done items serializes to
 * nothing and vanishes on the re-parse — every later card's real position shifts one left, so each
 * later card with done items was "replaced" at a stale index, destroying the unrelated card that
 * now sat there (its unchecked items included).
 */
class ClearDoneColumnIntegrityTest {

    private fun setup(name: String, md: String): Pair<Model, CanvasName> {
        val canvas = CanvasName(name)
        val model = Model(canvas)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        model.updateColumn(canvas, model.getCanvas(canvas)!!.firstNonLayout(), md)
        return model to canvas
    }

    @Test
    fun cardsBelowAFullyDoneCardSurviveClearDone() {
        val (model, canvas) = setup("_cdi-below",
            "* /all done 1\n* /all done 2\n" +          // loose card: vanishes once cleared
            "\n# Later\n* /done x\n* keep 1\n* keep 2\n" +
            "\n# Evening\n* /done y\n* keep 3\n* keep 4\n")

        model.clearDone(canvas)

        val raw = model.getCanvasColumnRaw(canvas, model.getCanvas(canvas)!!.firstNonLayout()) ?: ""
        listOf("keep 1", "keep 2", "keep 3", "keep 4").forEach {
            assertTrue(raw.contains(it), "unchecked '$it' must survive Clear Done; column now: <<$raw>>")
        }
        listOf("all done 1", "all done 2", "done x", "done y").forEach {
            assertFalse(raw.contains(it), "done '$it' must be cleared; column now: <<$raw>>")
        }
    }

    @Test
    fun clearDoneIsIdempotentOnAMixedColumn() {
        val (model, canvas) = setup("_cdi-idem",
            "# A\n* /done\n* keep a\n\n* /only done\n\n# B\n* keep b\n")

        model.clearDone(canvas)
        model.clearDone(canvas)   // second pass must be a clean no-op

        val raw = model.getCanvasColumnRaw(canvas, model.getCanvas(canvas)!!.firstNonLayout()) ?: ""
        assertTrue(raw.contains("keep a"), "column now: <<$raw>>")
        assertTrue(raw.contains("keep b"), "column now: <<$raw>>")
        assertFalse(raw.contains("done"))
    }
}
