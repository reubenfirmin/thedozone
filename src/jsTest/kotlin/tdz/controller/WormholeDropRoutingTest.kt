package tdz.controller

import kotlinx.css.Display
import kotlinx.css.display
import kotlinx.css.height
import kotlinx.css.px
import kotlinx.css.width
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.span
import tdz.controller.events.CardsReordered
import tdz.controller.events.CardsSentToCanvas
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.ColumnId
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.model.ref
import tdz.view.DomIds
import tdz.view.Zones
import web.cssom.ClassName
import web.dom.Element
import web.dom.ElementId
import web.dom.document
import web.html.HTMLElement
import web.pointer.POINTER_MOVE
import web.pointer.PointerEvent
import web.pointer.PointerEventInit
import zoned.framework.interop.addToBody
import zoned.framework.interop.css
import zoned.framework.libs.SortableDropEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression: the wormhole visual opens from a pointer hit-test, but drop routing used to depend on
 * Sortable parking its placeholder inside the link chip's own Sortable container — and Sortable's
 * placeholder shuffling lets the link card shift away ("dodge"), so a drop with the wormhole
 * visibly open frequently reordered the column instead of sending the card through. The contract
 * under test: WORMHOLE OPEN AT DROP = CARD GOES INTO THE LINKED CANVAS, wherever Sortable parked it.
 */
class WormholeDropRoutingTest {

    private val source = CanvasName("_whd-src")
    private val target = CanvasName("_whd-target")
    private val column = ColumnId(1)

    @AfterTest
    fun cleanup() {
        document.body.asDynamic()?.innerHTML = ""
    }

    private fun buildModel(): Model {
        val model = Model(source)
        model.addCanvas(canvasFromTemplate(source, null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(target, null, SINGLE_PANE))
        model.updateColumn(source, column, "# Card A\n[${target.normalized()} (link)]\n\n# Card B\n")
        return model
    }

    /** Build the column DOM: each card as a div with its positional id, the chip inside card A. */
    private fun buildDom(cards: List<Card>) {
        addToBody {
            div {
                id = DomIds.column(column)
                cards.forEachIndexed { i, card ->
                    div {
                        id = DomIds.card(card.ref)
                        css { width = 200.px; height = 40.px }
                        if (i == 0) {
                            span("canvas-drop-zone") {
                                id = DomIds.canvasLink(target)
                                attributes["data-canvas-name"] = target.normalized()
                                css { display = Display.inlineBlock; width = 80.px; height = 20.px }
                                +"❯❯ link"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun el(id: String): HTMLElement =
        document.getElementById(ElementId(id))
            ?: error("test setup: element $id not in DOM")

    private fun pointerMoveOver(target: Element) {
        val r = target.getBoundingClientRect()
        document.dispatchEvent(PointerEvent(
            PointerEvent.POINTER_MOVE,
            PointerEventInit(clientX = (r.left + r.width / 2).toInt(), clientY = (r.top + r.height / 2).toInt()),
        ))
    }

    private fun dropEvent(item: Element, container: Element) = SortableDropEvent(
        itemId = item.id,
        fromId = container.id,
        toId = container.id,
        oldIndex = 1,
        newIndex = 1,
        item = item,
        from = container,
        to = container,
    )

    @Test
    fun dropWithWormholeOpenSendsCardToLinkedCanvas_evenWhenSortableReportsTheColumn() {
        val model = buildModel()
        val cards = model.cards(source, column)
        buildDom(cards)
        val eventBus = EventBus<Event>()
        val dragDrop = DragDrop(model, eventBus)

        var sent: CardsSentToCanvas? = null
        var reordered = false
        eventBus.on<CardsSentToCanvas> { sent = it }
        eventBus.on<CardsReordered> { reordered = true }

        val cardB = el(DomIds.card(cards[1].ref))
        val columnEl = el(DomIds.column(column))
        val chip = el(DomIds.canvasLink(target))

        // Drag card B; the pointer moves over the link chip → the wormhole opens.
        dragDrop.beginPortalTracking(cardB)
        pointerMoveOver(chip)
        val portal = document.getElementById(ElementId(Zones.WORMHOLE.elementId))
        assertNotNull(portal, "the wormhole portal should exist once the pointer is over a link")
        assertTrue(portal.classList.contains(ClassName("tdz-wh-on")), "the wormhole should be open over the chip")

        // Drop. Sortable reports a plain column drop (its placeholder dodged out of the chip).
        dragDrop.handleDrop(dropEvent(cardB, columnEl), source)
        dragDrop.stopPortalTracking()

        val sentEvent = sent
        assertNotNull(sentEvent, "wormhole open at drop → the card must be sent to the linked canvas")
        assertEquals(target, sentEvent.targetCanvas)
        assertEquals(listOf(cards[1]), sentEvent.cards, "exactly the dragged card is sent — not the column's other cards")
        assertFalse(reordered, "the drop must not also reorder the column")
        assertFalse(cardB.isConnected, "the dragged element is removed from wherever Sortable parked it")
    }

    @Test
    fun draggingALinkCardCannotDropIntoItsOwnWormhole() {
        // Regression: dragging the card that OWNS the chip used to light its own wormhole, and
        // dropping swallowed the card into its own linked board (it vanished). Its chip must be
        // excluded from the portal scan, so the portal never opens and no send fires.
        val model = buildModel()
        val cards = model.cards(source, column)
        buildDom(cards)
        val eventBus = EventBus<Event>()
        val dragDrop = DragDrop(model, eventBus)

        var sent: CardsSentToCanvas? = null
        eventBus.on<CardsSentToCanvas> { sent = it }

        val cardA = el(DomIds.card(cards[0].ref))   // the card that CONTAINS the chip
        val columnEl = el(DomIds.column(column))
        val chip = el(DomIds.canvasLink(target))

        dragDrop.beginPortalTracking(cardA)
        pointerMoveOver(chip)

        val portal = document.getElementById(ElementId(Zones.WORMHOLE.elementId))
        val open = portal != null && portal.classList.contains(ClassName("tdz-wh-on"))
        assertFalse(open, "a card's own wormhole must not open while dragging that card")

        dragDrop.handleDrop(dropEvent(cardA, columnEl), source)
        dragDrop.stopPortalTracking()
        assertNull(sent, "dropping a link card on its own chip must NOT send it into its own board")
    }

    @Test
    fun aChipOutsideAnyColumnIsNotAWormholeTarget() {
        // Regression (intermittent): Sortable's floating drag mirror is a clone of the card — with a
        // copy of the chip — appended to <body>, OUTSIDE any column, and it follows the pointer. The
        // identity check alone misses that clone; only in-column chips may be wormhole targets.
        val model = buildModel()
        val cards = model.cards(source, column)
        buildDom(cards)
        // A stray chip on <body>, standing in for the drag mirror's cloned chip.
        addToBody {
            span("canvas-drop-zone") {
                id = "mirror-chip"
                attributes["data-canvas-name"] = target.normalized()
                css { display = Display.inlineBlock; width = 80.px; height = 20.px }
            }
        }
        val dragDrop = DragDrop(model, EventBus<Event>())
        val cardB = el(DomIds.card(cards[1].ref))
        val mirrorChip = el("mirror-chip")

        dragDrop.beginPortalTracking(cardB)
        pointerMoveOver(mirrorChip)

        val portal = document.getElementById(ElementId(Zones.WORMHOLE.elementId))
        val open = portal != null && portal.classList.contains(ClassName("tdz-wh-on"))
        assertFalse(open, "a chip outside any column (drag-mirror clone) must not open a wormhole")
        dragDrop.stopPortalTracking()
    }

    /**
     * The dodge fix: while the wormhole is open, Sortable must not relocate its placeholder —
     * every relocation reflows the column and shifts the link card out from under the pointer,
     * which would close the very portal the user is aiming at. [DragDrop.placeholderMovesAllowed]
     * is the onMove veto wired into every column's Sortable config.
     */
    @Test
    fun placeholderRelocationIsVetoedWhileWormholeIsOpen() {
        val model = buildModel()
        val cards = model.cards(source, column)
        buildDom(cards)
        val dragDrop = DragDrop(model, EventBus<Event>())

        val cardB = el(DomIds.card(cards[1].ref))
        val chip = el(DomIds.canvasLink(target))

        dragDrop.beginPortalTracking(cardB)
        assertTrue(dragDrop.placeholderMovesAllowed(), "no wormhole yet → placeholder moves freely")

        pointerMoveOver(chip)
        assertFalse(dragDrop.placeholderMovesAllowed(), "wormhole open → the column layout is frozen")

        document.dispatchEvent(PointerEvent(
            PointerEvent.POINTER_MOVE,
            PointerEventInit(clientX = 4000, clientY = 4000),
        ))
        assertTrue(dragDrop.placeholderMovesAllowed(), "wormhole closed → placeholder moves again")
        dragDrop.stopPortalTracking()
    }

    /**
     * Hysteresis: a relocation animation already in flight when the freeze engages can still slide
     * the chip a little. The wormhole must not flicker off the instant the chip drifts past the
     * 16px ENTER margin — it stays latched until the pointer is well clear (the RELEASE margin).
     */
    @Test
    fun wormholeStaysLatchedWithinReleaseMargin() {
        val model = buildModel()
        val cards = model.cards(source, column)
        buildDom(cards)
        val dragDrop = DragDrop(model, EventBus<Event>())

        val cardB = el(DomIds.card(cards[1].ref))
        val chip = el(DomIds.canvasLink(target))

        dragDrop.beginPortalTracking(cardB)
        pointerMoveOver(chip)
        val portal = document.getElementById(ElementId(Zones.WORMHOLE.elementId))
        assertNotNull(portal)
        assertTrue(portal.classList.contains(ClassName("tdz-wh-on")), "setup: wormhole open over the chip")

        // Drift to ~30px below the chip: outside the 16px enter margin, inside the release margin.
        // (Pointer moving off the chip is geometrically the same as the chip sliding away.)
        val r = chip.getBoundingClientRect()
        document.dispatchEvent(PointerEvent(
            PointerEvent.POINTER_MOVE,
            PointerEventInit(clientX = (r.left + r.width / 2).toInt(), clientY = (r.bottom + 30).toInt()),
        ))
        assertTrue(portal.classList.contains(ClassName("tdz-wh-on")),
            "a small drift must not close the wormhole (it would re-enable dodging mid-aim)")
        assertFalse(dragDrop.placeholderMovesAllowed(), "still latched → still frozen")

        // Far away: well past the release margin → the wormhole lets go.
        document.dispatchEvent(PointerEvent(
            PointerEvent.POINTER_MOVE,
            PointerEventInit(clientX = 4000, clientY = 4000),
        ))
        assertFalse(portal.classList.contains(ClassName("tdz-wh-on")), "far from the chip → wormhole closes")
        dragDrop.stopPortalTracking()
    }

    /**
     * The hit-test works from a snapshot of the chip elements taken at drag start (columns don't
     * re-render mid-drag, so a per-pointermove querySelectorAll was pure overhead). A chip that IS
     * removed mid-drag (a sync pull re-rendering its column) must simply stop participating.
     */
    @Test
    fun chipRemovedMidDragStopsParticipatingInHitTest() {
        val model = buildModel()
        val cards = model.cards(source, column)
        buildDom(cards)
        val dragDrop = DragDrop(model, EventBus<Event>())

        val cardB = el(DomIds.card(cards[1].ref))
        val chip = el(DomIds.canvasLink(target))
        val r = chip.getBoundingClientRect()

        dragDrop.beginPortalTracking(cardB)
        chip.remove()
        document.dispatchEvent(PointerEvent(
            PointerEvent.POINTER_MOVE,
            PointerEventInit(clientX = (r.left + r.width / 2).toInt(), clientY = (r.top + r.height / 2).toInt()),
        ))

        val portal = document.getElementById(ElementId(Zones.WORMHOLE.elementId))
        assertTrue(portal == null || !portal.classList.contains(ClassName("tdz-wh-on")),
            "a removed chip must not light the wormhole")
        dragDrop.stopPortalTracking()
    }

    /**
     * Regression: when a canvas-link chip points to a NESTED board (e.g. "parent/child"), the chip's
     * absolute target must survive the drop-routing decode intact. Before the fix, DragDrop decoded
     * the target canvas from the element id, which CardRenderer set to the bare leaf ("child"); the
     * drop sent the card to a new top-level board "child" instead of the nested "parent/child".
     *
     * After the fix, DragDrop reads the `data-canvas-name` attribute (which CardRenderer sets to
     * absoluteTarget.normalized(), the full path). This test builds the chip exactly as the fixed
     * CardRenderer would, then asserts the drop delivers the card to the absolute path.
     */
    @Test
    fun dropOnNestedCanvasLinkChipSendsCardToAbsolutePath() {
        // The current board; its child "child" is the absolute target for the chip.
        val parentBoard = CanvasName("_whd-parent")
        val absoluteChildBoard = CanvasName("_whd-parent/child")

        val model = Model(parentBoard)
        model.addCanvas(canvasFromTemplate(parentBoard, null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(absoluteChildBoard, CanvasName("_whd-parent"), SINGLE_PANE))

        val col = ColumnId(1)
        model.updateColumn(parentBoard, col, "# Card A\n[child (link)]\n\n# Card B\n")

        val cards = model.cards(parentBoard, col)
        // Build the DOM the way the FIXED CardRenderer does:
        //   id         = DomIds.canvasLink(link.canvasName)   — bare leaf, safe in selectors
        //   data-canvas-name = absoluteTarget.normalized()     — full path, used for drop routing
        val leafName = CanvasName("child")          // what link.canvasName is (the bare link)
        addToBody {
            div {
                id = DomIds.column(col)
                cards.forEachIndexed { i, card ->
                    div {
                        id = DomIds.card(card.ref)
                        css { width = 200.px; height = 40.px }
                        if (i == 0) {
                            span("canvas-drop-zone") {
                                // id uses leaf (same as current CardRenderer — unchanged by fix)
                                id = DomIds.canvasLink(leafName)
                                // data-canvas-name carries the absolute path (the fix)
                                attributes["data-canvas-name"] = absoluteChildBoard.normalized()
                                css { display = Display.inlineBlock; width = 80.px; height = 20.px }
                                +"❯❯ child"
                            }
                        }
                    }
                }
            }
        }

        val eventBus = EventBus<Event>()
        val dragDrop = DragDrop(model, eventBus)
        var sent: CardsSentToCanvas? = null
        eventBus.on<CardsSentToCanvas> { sent = it }

        val cardB = el(DomIds.card(cards[1].ref))
        val columnEl = el(DomIds.column(col))
        val chip = el(DomIds.canvasLink(leafName))

        dragDrop.beginPortalTracking(cardB)
        pointerMoveOver(chip)
        assertNotNull(document.getElementById(ElementId(Zones.WORMHOLE.elementId)), "wormhole portal should exist")

        dragDrop.handleDrop(dropEvent(cardB, columnEl), parentBoard)
        dragDrop.stopPortalTracking()

        val sentEvent = assertNotNull(sent, "wormhole open → card must be sent through the wormhole")
        assertEquals(
            absoluteChildBoard, sentEvent.targetCanvas,
            "drop must target the absolute board 'parent/child', not the bare leaf 'child'"
        )
    }

    /**
     * Regression: a `..` parent-link on a child of the ROOT points to the root board, whose
     * normalized name is "". Drop routing used to reject an empty `data-canvas-name` with
     * `takeIf { isNotEmpty() }`, so dropping onto such a card did nothing. Presence of the attribute —
     * not non-emptiness — is what marks a link drop; "" is a valid (root) target.
     */
    @Test
    fun dropOnRootParentLinkChipSendsCardToRoot() {
        val root = CanvasName("")
        val childBoard = CanvasName("Work")
        val model = Model(childBoard)
        model.addCanvas(canvasFromTemplate(root, null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(childBoard, root, SINGLE_PANE))
        val col = ColumnId(1)
        model.updateColumn(childBoard, col, "# Card A\n[.. (Home)]\n\n# Card B\n")

        val cards = model.cards(childBoard, col)
        val leaf = CanvasName("..")
        addToBody {
            div {
                id = DomIds.column(col)
                cards.forEachIndexed { i, card ->
                    div {
                        id = DomIds.card(card.ref)
                        css { width = 200.px; height = 40.px }
                        if (i == 0) {
                            span("canvas-drop-zone") {
                                id = DomIds.canvasLink(leaf)
                                attributes["data-canvas-name"] = root.normalized()   // "" — the root board
                                css { display = Display.inlineBlock; width = 80.px; height = 20.px }
                                +"❯❯ Home"
                            }
                        }
                    }
                }
            }
        }

        val eventBus = EventBus<Event>()
        val dragDrop = DragDrop(model, eventBus)
        var sent: CardsSentToCanvas? = null
        eventBus.on<CardsSentToCanvas> { sent = it }

        val cardB = el(DomIds.card(cards[1].ref))
        val columnEl = el(DomIds.column(col))
        val chip = el(DomIds.canvasLink(leaf))

        dragDrop.beginPortalTracking(cardB)
        pointerMoveOver(chip)
        dragDrop.handleDrop(dropEvent(cardB, columnEl), childBoard)
        dragDrop.stopPortalTracking()

        val sentEvent = assertNotNull(sent, "wormhole open over a ..→root link → the card must be sent")
        assertEquals(root, sentEvent.targetCanvas, "a `..` link to the root must route the drop to the root board")
    }

    @Test
    fun dropWithWormholeClosedReordersNormally() {
        val model = buildModel()
        val cards = model.cards(source, column)
        buildDom(cards)
        val eventBus = EventBus<Event>()
        val dragDrop = DragDrop(model, eventBus)

        var sent = false
        var reordered: CardsReordered? = null
        eventBus.on<CardsSentToCanvas> { sent = true }
        eventBus.on<CardsReordered> { reordered = it }

        val cardB = el(DomIds.card(cards[1].ref))
        val columnEl = el(DomIds.column(column))

        // Drag card B with the pointer nowhere near the chip → wormhole stays closed.
        dragDrop.beginPortalTracking(cardB)
        document.dispatchEvent(PointerEvent(
            PointerEvent.POINTER_MOVE,
            PointerEventInit(clientX = 4000, clientY = 4000),
        ))

        dragDrop.handleDrop(dropEvent(cardB, columnEl), source)
        dragDrop.stopPortalTracking()

        assertFalse(sent, "no wormhole open → nothing is sent to another canvas")
        assertNotNull(reordered, "a plain drop still reorders the column")
    }
}
