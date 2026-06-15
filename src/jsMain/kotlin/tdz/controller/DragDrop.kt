package tdz.controller

import tdz.controller.events.CardsReordered
import tdz.controller.events.CardsSentToCanvas
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.Card.Companion.cardsUnderH1
import tdz.model.CardRef
import tdz.model.ref
import tdz.model.Model
import js.string.asString
import kotlinx.css.outlineOffset
import kotlinx.css.px
import web.cssom.ClassName
import web.dom.Element
import web.dom.ElementId
import web.dom.document
import web.events.AddEventListenerOptions
import web.events.addEventListener
import web.html.HTMLElement
import web.pointer.POINTER_DOWN
import web.pointer.POINTER_MOVE
import web.pointer.PointerEvent
import tdz.view.CssClasses
import tdz.view.DomIds
import tdz.view.WormholePortal
import tdz.view.theme.DragPreviewFill
import tdz.view.theme.ThemeManager
import zoned.framework.interop.clear
import zoned.framework.interop.important
import tdz.view.theme.themedStyleSheet
import zoned.framework.libs.SortableConfig
import zoned.framework.libs.makeSortable
import zoned.framework.libs.sortableSelect
import zoned.framework.libs.sortableDeselect

/**
 * Manages drag and drop functionality for cards using Sortable.js
 */
class DragDrop(private val model: Model, private val eventBus: EventBus<Event>) {

    /**
     * Elements we put into the MultiDrag selection for the current grab. Tracked so a header that
     * is grabbed but never dragged doesn't leave its group selected and contaminate the next drag.
     */
    private val selected = mutableListOf<Element>()

    /** The capture-phase pointerdown listener is global; install it exactly once. */
    private var headerHookInstalled = false

    /** Group size / pixel footprint of the current grab, measured at pointerdown before MultiDrag
     *  folds the children away, then consumed once by [decorateGroupDrag]. Count <= 1 = no group. */
    private var pendingGroupCount = 0
    private var pendingGroupHeight = 0.0

    /** Deep clones of the grabbed group's cards, captured at pointerdown (before MultiDrag removes
     *  them from the column) so the floating mirror can show the actual cards being moved. */
    private val groupClones = mutableListOf<Element>()

    // --- Wormhole portal (canvas-link drop) state ---
    /** True while a card drag is in flight; gates the global pointermove hit-test. */
    private var dragActive = false
    /** The document pointermove hit-test is global; install it exactly once. */
    private var portalHookInstalled = false
    /** The canvas-link drop zone currently under the pointer (so we only re-show on change). */
    private var activeLink: Element? = null
    /** The chip elements, snapshotted at drag start: columns never re-render mid-drag, so a
     *  per-pointermove querySelectorAll was pure overhead. Rects are still read live (chips shift
     *  with column reflow); an element that leaves the DOM mid-drag just stops participating. */
    private var dropZones: List<Element> = emptyList()
    /** Last pointer position + the dragged card's size, captured for the swallow animation. */
    private var lastPointerX = 0.0
    private var lastPointerY = 0.0
    private var dragCardW = 160.0
    private var dragCardH = 80.0

    /**
     * Set up drag and drop for a column
     */
    fun setupColumn(elementId: String, canvasName: CanvasName) {
        ensureDragStyles()
        installHeaderSelectHook()
        makeSortable(SortableConfig(
            elementId = ElementId(elementId),
            group = "cards",
            animation = 150,
            draggable = DomIds.CARD_SELECTOR,
            // placeholder (drop preview): solid rectangle, contents hidden; mirror: opaque card
            ghostClass = CssClasses.CARD_GHOST,
            chosenClass = CssClasses.CARD_CHOSEN,
            dragClass = CssClasses.CARD_DRAG,
            // Sortable's managed mirror (lifted to body) instead of the native drag image —
            // smooth across the nested scroll/grid columns; larger threshold so cards target
            // (and paint the ghost into) columns readily, including empty ones.
            forceFallback = true,
            fallbackOnBody = true,
            fallbackTolerance = 4,
            // 0 disables Sortable's "empty list" detection halo. With a halo (>0), every EMPTY column
            // gets an insertion zone extending that many px past its rect; since inter-column gaps are
            // only 0–16px the halos of adjacent empty columns overlap, and the placeholder ping-pongs
            // between them as the ghost itself flips each column's empty/non-empty state on every move.
            // Columns are large full-height areas, so a direct hover still inserts the ghost (Sortable's
            // `target === el` path) — the halo only added flicker, never reachability.
            emptyInsertThreshold = 0,
            // Dragging a section header carries its cards: the MultiDrag plugin drags every
            // selected element together, and a pointerdown hook (installed above) selects the
            // header + its run before the drag begins so the whole stack lifts as one.
            // multiDragKey neutralises click-to-select so normal card clicks (links, navigation)
            // keep working — selection is purely programmatic here.
            multiDrag = true,
            selectedClass = CssClasses.CARD_SELECTED,
            multiDragKey = "CTRL",
            // MultiDrag folds the other selected cards away during the drag, so by default both the
            // floating mirror and the drop placeholder are just the header. Decorate them to reflect
            // the whole group: a stacked mirror with a count, and a placeholder sized to the group.
            onStart = { el -> decorateGroupDrag(); beginPortalTracking(el) },
            // Freeze the layout while the wormhole is open — see [placeholderMovesAllowed].
            onMove = { placeholderMovesAllowed() },
            onEnd = { event ->
                handleDrop(event, canvasName)
                stopPortalTracking()
            }
        ))
    }

    /**
     * Install (once) a capture-phase pointerdown listener that pre-selects a section header and the
     * cards beneath it, so the MultiDrag plugin carries the whole group. It must run before
     * Sortable's own pointerdown handler — which decides single-vs-multi drag from the current
     * selection — so we listen on the document in the capture phase. Selecting later (e.g. in
     * Sortable's onChoose) is too late: the plugin has already locked in a single-item drag.
     */
    private fun installHeaderSelectHook() {
        if (headerHookInstalled) return
        headerHookInstalled = true
        document.addEventListener(
            PointerEvent.POINTER_DOWN,
            { event -> onPointerDown(event) },
            AddEventListenerOptions(capture = true),
        )
    }

    /**
     * On grabbing a section header (a card marked data-h1only), select it and every following card
     * in the column up to the next header or the column end. A plain card — or a header with no
     * cards under it — selects nothing and drags alone.
     */
    private fun onPointerDown(event: PointerEvent) {
        clearSelection()   // wipe any stale selection from a prior grab before selecting anew
        pendingGroupCount = 0
        val card = (event.target as? Element)?.closest(DomIds.CARD_SELECTOR) ?: return
        if (card.getAttribute("data-h1only") != "true") return
        select(card)
        var sib = card.nextElementSibling
        while (sib != null) {
            val id = sib.id.asString()
            if (!DomIds.isCard(id) || sib.getAttribute("data-h1only") == "true") break
            select(sib)
            sib = sib.nextElementSibling
        }
        groupClones.clear()
        if (selected.size > 1) {
            // Footprint = top of the header to the bottom of the last card (captures the cards plus
            // the margins between them). Measured now, before MultiDrag removes them from the column.
            val top = selected.first().getBoundingClientRect().top
            val bottom = selected.last().getBoundingClientRect().bottom
            pendingGroupCount = selected.size
            pendingGroupHeight = bottom - top
            // Snapshot the actual cards now (deep clones keep their inline card styling) so the
            // mirror can render the real stack; the originals are about to be folded out of view.
            selected.forEach { groupClones += it.cloneNode(true) as Element }
        }
    }

    /**
     * Make a group drag look like the group of cards actually moving: replace the floating mirror's
     * contents (MultiDrag leaves it as just the header) with clones of the real cards stacked as
     * they sit in the column, and grow the drop placeholder to the group's measured height so the
     * gap shows where the whole section will land. Single-card drags are left untouched.
     */
    private fun decorateGroupDrag() {
        if (pendingGroupCount <= 1) return
        val height = pendingGroupHeight
        pendingGroupCount = 0
        (document.querySelector(".${CssClasses.CARD_DRAG}") as? HTMLElement)?.let { mirror ->
            mirror.classList.add(ClassName(CssClasses.CARD_DRAG_GROUP))
            mirror.style.height = "auto"   // grow past the header-sized box Sortable created
            mirror.clear()
            groupClones.forEach { clone ->
                clone.removeAttribute("id")                  // avoid duplicate ids with the originals
                clone.classList.remove(                      // drop the selection outline on the clones
                    ClassName(CssClasses.CARD_SELECTED),
                    ClassName("sortable-chosen"),
                )
                mirror.appendChild(clone)
            }
        }
        groupClones.clear()
        if (height > 0) {
            (document.querySelector(".${CssClasses.CARD_GHOST}") as? HTMLElement)?.style?.minHeight = "${height}px"
        }
    }

    private fun select(el: Element) {
        sortableSelect(el)
        selected += el
    }

    private fun clearSelection() {
        // Only deselect elements still in the document — a completed drop re-renders the column,
        // detaching the old elements, and sortableDeselect dereferences el.parentNode.
        selected.forEach { if (it.isConnected) sortableDeselect(it) }
        selected.clear()
    }

    // --- Wormhole portal: hover detection + lifecycle -----------------------------------------
    //
    // Canvas-link chips are NOT Sortable containers. They used to be (so Sortable could report
    // `event.to` = chip), but Sortable parking its hidden ghost in the tiny chip collapsed the
    // column gap and shifted the link card on every approach — the chip "dodged" the pointer, and
    // the drop routed by where the ghost happened to sit, not by what the user saw. Instead the
    // pointer hit-test below is the single source of truth: it lights the portal AND routes the
    // drop ([handleDrop] checks [activeLink]), so the visual and the behavior cannot disagree.

    /** A drag started: record the dragged card's size, start tracking, install the pointer hit-test.
     *  (internal for tests — production entry is Sortable's onStart) */
    internal fun beginPortalTracking(dragged: Element) {
        dragActive = true
        val r = dragged.getBoundingClientRect()
        if (r.width > 0) dragCardW = r.width
        if (r.height > 0) dragCardH = r.height
        dropZones = document.querySelectorAll(".${CssClasses.DROP_ZONE}").let { zones ->
            (0 until zones.length).mapNotNull { zones[it] }
        }
        installPortalHook()
    }

    /** Drag ended: stop tracking and fade the hover vortex (a drop-swallow flash plays on regardless).
     *  (internal for tests — production entry is Sortable's onEnd) */
    internal fun stopPortalTracking() {
        dragActive = false
        activeLink = null
        dropZones = emptyList()
        WormholePortal.hide()
    }

    /** Install (once) a capture-phase pointermove that lights up the vortex over a hovered link. */
    private fun installPortalHook() {
        if (portalHookInstalled) return
        portalHookInstalled = true
        document.addEventListener(
            PointerEvent.POINTER_MOVE,
            { event -> onPortalPointerMove(event) },
            AddEventListenerOptions(capture = true),
        )
    }

    /**
     * Sortable's onMove veto for every column: placeholder relocations are allowed only while no
     * wormhole is open. The moment the portal lights up, the drop is routed to the linked canvas
     * ([handleDrop]), so further placeholder shuffling is pointless — and actively harmful: each
     * relocation reflows the column and shifts the link card out from under the pointer (the
     * "dodge"), closing the very portal the user is aiming at. Frozen = nothing moves = stable aim.
     */
    internal fun placeholderMovesAllowed(): Boolean = activeLink == null

    /** Open the portal when the pointer comes this close to a chip. */
    private val enterMargin = 16.0
    /** Once open, keep it until the pointer is this far away — a relocation animation already in
     *  flight when the freeze engages can still slide the chip a little past [enterMargin], and
     *  that drift must not flicker the portal off mid-aim. */
    private val releaseMargin = 48.0

    /**
     * While a card is being dragged, light the vortex over whichever canvas-link the pointer is within
     * (a small catch-margin past the chip), and clear it when the pointer leaves. Pure hit-testing —
     * independent of where Sortable has parked its ghost — so the visual is reliable and self-contained.
     * Asymmetric margins (enter close, release far) latch the open portal against small chip drift.
     */
    private fun onPortalPointerMove(event: PointerEvent) {
        if (!dragActive) return
        lastPointerX = event.clientX.toDouble()
        lastPointerY = event.clientY.toDouble()

        fun within(z: Element, margin: Double): Boolean {
            val r = z.getBoundingClientRect()
            return lastPointerX in (r.left - margin)..(r.right + margin) &&
                lastPointerY in (r.top - margin)..(r.bottom + margin)
        }

        // Latched: the active link holds (no rescan) while the pointer stays within the release range.
        val held = activeLink?.takeIf { it.isConnected && within(it, releaseMargin) }
        val hit = held ?: dropZones.firstOrNull { it.isConnected && within(it, enterMargin) }

        if (hit !== activeLink) {
            activeLink = hit
            if (hit == null) WormholePortal.hide()
        }
        // Reposition on EVERY move, not just on change: an in-flight reflow can still drift the chip
        // a little, and the vortex must stay glued to it — an "open" portal sitting over empty space
        // would lie about where the drop goes.
        (hit as? HTMLElement)?.let { WormholePortal.showOver(it) }
    }

    /**
     * Inject/refresh the drag styling from the active theme. The drop placeholder (ghostClass) is a
     * solid rectangle with its contents hidden; the floating mirror (dragClass) is opaque with a
     * solid background + shadow. Colors come from the typed theme (interpolated into the rule —
     * an injected stylesheet is inherently a string, but the source stays typed).
     */
    private fun ensureDragStyles() {
        val theme = ThemeManager.current
        // AppBackground paints the page background fixed to the viewport, so the preview cuts
        // straight through the (translucent) column to the real background; Image paints a custom
        // image on the preview itself (e.g. a spotlight); Solid = the dragPlaceholder color alone.
        val phImg = when (val fill = theme.dragPlaceholderFill) {
            DragPreviewFill.Solid -> "none"
            DragPreviewFill.AppBackground -> theme.appBgImage
            is DragPreviewFill.Image -> fill.css
        }
        val phAttach = if (theme.dragPlaceholderFill == DragPreviewFill.AppBackground) "fixed" else "scroll"
        // !important throughout: these must beat Sortable's inline styles and the cards' own
        // inline css{}.
        themedStyleSheet("tdz-drag-styles") {
            rule(".${CssClasses.CARD_GHOST}") {
                important("background-color", theme.dragPlaceholder)
                important("background-image", phImg)
                important("background-attachment", phAttach)
                important("background-size", "cover")
                important("border-color", theme.dragPlaceholder)
                important("border-radius", "0")
                important("opacity", "1")
                important("box-shadow", "none")
            }
            rule(".${CssClasses.CARD_GHOST} *") { important("visibility", "hidden") }
            rule(".${CssClasses.CARD_CHOSEN}") { important("background-color", theme.dragMirrorBg) }
            rule(".${CssClasses.CARD_DRAG}") {
                important("opacity", "1")
                important("background-color", theme.dragMirrorBg)
                important("border-radius", "0")
                important("box-shadow", "5px 5px 0 ${theme.cardShadow}")
            }
            rule(".${CssClasses.CARD_SELECTED}") {
                important("outline", "2px dashed ${theme.dragPlaceholder}")
                outlineOffset = (-2).px
            }
            // Group drag: the mirror becomes a transparent container holding clones of the real cards
            // (each clone keeps its own background/border/shadow), so it reads as the actual stack
            // of cards moving. The placeholder is grown to the group height inline.
            rule(".${CssClasses.CARD_DRAG_GROUP}") {
                important("background-color", "transparent")
                important("background-image", "none")
                important("box-shadow", "none")
                important("padding", "0")
            }
            // Each cloned card gets the opaque drag background (themes like rainbow use translucent
            // card fills, which would show the page through the floating stack).
            rule(".${CssClasses.CARD_DRAG_GROUP} .${CssClasses.CARD}") {
                important("background-color", theme.dragMirrorBg)
                important("background-image", "none")
            }
        }
    }

    /** (internal for tests — production entry is Sortable's onEnd) */
    internal fun handleDrop(event: zoned.framework.libs.SortableDropEvent, canvasName: CanvasName) {
        // Dropped while the wormhole is open → send the card(s) into the linked canvas. Routed by
        // the SAME pointer hit-test that lights the portal ([activeLink], still set: onEnd calls
        // this before stopPortalTracking) — NOT by Sortable's drop target, which reflects wherever
        // the placeholder happened to sit after shuffling cards around. Wormhole open = card goes in.
        val linkEl = activeLink as? HTMLElement
        val targetCanvas = linkEl?.let { DomIds.canvasLinkName(it.id.asString()) }
        if (linkEl != null && targetCanvas != null) {
            handleWormholeDrop(event, canvasName, targetCanvas, linkEl)
            return
        }

        val fromColumnId = DomIds.columnId(event.fromId.asString())
        val toColumnId = DomIds.columnId(event.toId.asString())
        if (fromColumnId == null || toColumnId == null) {
            console.warn("Could not extract column IDs from: ${event.fromId.asString()}, ${event.toId.asString()}")
            return
        }

        // Sortable has already arranged the DOM. Derive the new ordering of each affected
        // column directly from its card elements' DOM order — no index arithmetic. Map each
        // card element back to its model card (its id still encodes its pre-drop position),
        // building both lists from the current model BEFORE persisting either.
        val newToCards = cardsInDomOrder(event.to, canvasName).toMutableList()
        val newFromCards = if (fromColumnId == toColumnId) null
            else cardsInDomOrder(event.from, canvasName).toMutableList()

        // Dragging a section header (an h1-only card) carries the cards under it — the run down
        // to the next h1 or the column end. Sortable only moved the header element, so reunite
        // its children with it: pull them out of wherever they still sit and re-insert them
        // immediately after the header in its new position. Identity is the pre-drop (column,
        // index) the DOM ids still encode.
        //
        // INVARIANT: the model must not have been mutated between Sortable's DOM change and this
        // point — every CardRef decoded from the DOM resolves against the PRE-drop model (the
        // Controller persists only when CardsReordered fires below). Anything that writes the
        // column between drop and here (an interleaved editor save, a sync pull applying remotely)
        // would make these refs resolve to the wrong cards.
        val header = DomIds.cardId(event.itemId.asString())
        var groupRelocated = false
        if (header != null) {
            val children = model.cards(canvasName, header.columnId).cardsUnderH1(header.index)
            // Sortable always drops the header element into the target column, so it is present in
            // newToCards; only relocate the children once we've found it (guards against losing
            // cards if that invariant ever fails to hold).
            val headerPos = newToCards.indexOfFirst { it.ref == header }
            if (children.isNotEmpty() && headerPos >= 0) {
                val childRefs = children.map { it.ref }.toSet()
                newToCards.removeAll { it.ref in childRefs }
                newFromCards?.removeAll { it.ref in childRefs }
                // Re-find the header: removing children above it (same-column drag) shifts its index.
                val pos = newToCards.indexOfFirst { it.ref == header }
                newToCards.addAll(pos + 1, children)
                groupRelocated = true
            }
        }

        // Drop done: drop the MultiDrag selection (the Controller's re-render rebuilds the elements
        // anyway, but this keeps our tracking honest and avoids leaking selection state).
        clearSelection()

        // Single-card drag: Sortable already arranged the DOM exactly as being persisted, so a
        // rebuild would only re-create what the browser is showing. Renumber the positional ids in
        // place (they must encode the NEW order for the next drop/toggle to decode correct refs)
        // and tell the Controller to skip the render. Group drags can't skip: the model just
        // reunited section children that Sortable never moved in the DOM.
        val domAlreadyOrdered = !groupRelocated
        if (domAlreadyOrdered) {
            renumberCardIds(event.to)
            if (event.from !== event.to) renumberCardIds(event.from)
        }

        // The Controller persists and emits ColumnsChanged (sync) + RefreshColumns (render).
        eventBus.fire(CardsReordered(canvasName, toColumnId, newToCards, fromColumnId, newFromCards, domAlreadyOrdered))
    }

    /** Rewrite [container]'s card element ids to encode their current DOM order. */
    private fun renumberCardIds(container: Element) {
        val columnId = DomIds.columnId(container.id.asString()) ?: return
        val children = container.children
        var index = 0
        for (i in 0 until children.length) {
            val el = children[i]
            if (DomIds.cardId(el.id.asString()) == null) continue
            el.id = ElementId(DomIds.card(CardRef(columnId, index++)))
        }
    }

    /** Map a column's card elements, in DOM order, back to their model [Card]s. */
    private fun cardsInDomOrder(container: Element, canvasName: CanvasName): List<Card> {
        val children = container.children
        val cards = mutableListOf<Card>()
        for (i in 0 until children.length) {
            val id = children[i].id.asString()
            val ref = DomIds.cardId(id) ?: continue
            cards += model.getCardByIndex(canvasName, ref)
        }
        return cards
    }

    /**
     * Move the dragged card(s) into the canvas [linkEl] points to, with the wormhole "swallow"
     * visual. The set to move is the elements that were actually dragged — the MultiDrag group when
     * a header grab selected one, else the single item — decoded from their ids (pre-drop refs).
     * It is NOT read from `event.to`'s children: that is wherever Sortable parked the drop (usually
     * a column), and the column's other cards must not be swept along.
     *
     * Per the design the user stays on the current canvas (no SwitchToCanvas) — the cards just vanish
     * into the portal.
     */
    private fun handleWormholeDrop(
        event: zoned.framework.libs.SortableDropEvent,
        sourceCanvas: CanvasName,
        targetCanvas: CanvasName,
        linkEl: HTMLElement,
    ) {
        val draggedEls = if (selected.isNotEmpty()) selected.toList() else listOf(event.item)
        val dragged = draggedEls
            .mapNotNull { DomIds.cardId(it.id.asString()) }
            .map { model.getCardByIndex(sourceCanvas, it) }
        if (dragged.isEmpty()) {
            clearSelection()
            return
        }

        // Cosmetic: flash the vortex and spiral a clone of the primary card into its core. Started
        // before the removal/refresh below detaches the real element; the clone is independent.
        val cx = if (lastPointerX != 0.0) lastPointerX else linkEl.getBoundingClientRect().let { it.left + it.width / 2 }
        val cy = if (lastPointerY != 0.0) lastPointerY else linkEl.getBoundingClientRect().let { it.top + it.height / 2 }
        (event.item as? HTMLElement)?.let { card ->
            WormholePortal.swallow(linkEl, card, cx, cy, dragCardW, dragCardH)
        }

        clearSelection()
        // Sortable parked the dragged element(s) wherever its placeholder last sat — possibly a
        // column the Controller won't re-render (it refreshes only the cards' SOURCE columns).
        // Remove them so no stale element lingers there.
        draggedEls.forEach { it.remove() }
        // The Controller moves the cards and emits the change events for both canvases.
        eventBus.fire(CardsSentToCanvas(sourceCanvas, targetCanvas, dragged))
    }
}
