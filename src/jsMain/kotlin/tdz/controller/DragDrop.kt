package tdz.controller

import tdz.controller.events.EventBus
import tdz.controller.events.RefreshColumns
import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.Card.Companion.cardsUnderH1
import tdz.model.Model
import js.string.asString
import web.dom.Element
import web.dom.ElementId
import web.dom.document
import web.html.HTMLElement
import web.html.HTMLStyleElement
import tdz.view.WormholePortal
import tdz.view.theme.ThemeManager
import zoned.framework.interop.getHtmlElement
import zoned.framework.libs.SortableConfig
import zoned.framework.libs.makeSortable
import zoned.framework.libs.sortableSelect
import zoned.framework.libs.sortableDeselect

/**
 * Manages drag and drop functionality for cards using Sortable.js
 */
class DragDrop(private val model: Model, private val eventBus: EventBus) {

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
            draggable = "[id^='card-']",
            // placeholder (drop preview): solid rectangle, contents hidden; mirror: opaque card
            ghostClass = "tdz-card-ghost",
            chosenClass = "bg-blue-100",
            dragClass = "tdz-card-drag",
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
            selectedClass = "tdz-card-selected",
            multiDragKey = "CTRL",
            // MultiDrag folds the other selected cards away during the drag, so by default both the
            // floating mirror and the drop placeholder are just the header. Decorate them to reflect
            // the whole group: a stacked mirror with a count, and a placeholder sized to the group.
            onStart = { el -> decorateGroupDrag(); beginPortalTracking(el) },
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
        document.asDynamic().addEventListener("pointerdown", { event: dynamic -> onPointerDown(event) }, true)
    }

    /**
     * On grabbing a section header (a card marked data-h1only), select it and every following card
     * in the column up to the next header or the column end. A plain card — or a header with no
     * cards under it — selects nothing and drags alone.
     */
    private fun onPointerDown(event: dynamic) {
        clearSelection()   // wipe any stale selection from a prior grab before selecting anew
        pendingGroupCount = 0
        val card = event.target?.closest("[id^='card-']") ?: return
        if (card.getAttribute("data-h1only") != "true") return
        select(card.unsafeCast<Element>())
        var sib: dynamic = card.nextElementSibling
        while (sib != null) {
            val id = sib.id as? String ?: break
            if (!id.startsWith("card-") || sib.getAttribute("data-h1only") == "true") break
            select(sib.unsafeCast<Element>())
            sib = sib.nextElementSibling
        }
        groupClones.clear()
        if (selected.size > 1) {
            // Footprint = top of the header to the bottom of the last card (captures the cards plus
            // the margins between them). Measured now, before MultiDrag removes them from the column.
            val top = selected.first().asDynamic().getBoundingClientRect().top as Double
            val bottom = selected.last().asDynamic().getBoundingClientRect().bottom as Double
            pendingGroupCount = selected.size
            pendingGroupHeight = bottom - top
            // Snapshot the actual cards now (deep clones keep their inline card styling) so the
            // mirror can render the real stack; the originals are about to be folded out of view.
            selected.forEach { groupClones += it.asDynamic().cloneNode(true).unsafeCast<Element>() }
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
        document.querySelector(".tdz-card-drag")?.let { mirror ->
            val m = mirror.asDynamic()
            m.classList.add("tdz-card-drag-group")
            m.style.height = "auto"   // grow past the header-sized box Sortable created
            m.innerHTML = ""
            groupClones.forEach { clone ->
                val c = clone.asDynamic()
                c.removeAttribute("id")                  // avoid duplicate ids with the originals
                c.classList.remove("tdz-card-selected")  // drop the selection outline on the clones
                c.classList.remove("sortable-chosen")
                mirror.appendChild(clone)
            }
        }
        groupClones.clear()
        if (height > 0) {
            document.querySelector(".tdz-card-ghost")?.asDynamic()?.style?.minHeight = "${height}px"
        }
    }

    private fun select(el: Element) {
        sortableSelect(el)
        selected += el
    }

    private fun clearSelection() {
        // Only deselect elements still in the document — a completed drop re-renders the column,
        // detaching the old elements, and sortableDeselect dereferences el.parentNode.
        selected.forEach { if (it.asDynamic().isConnected == true) sortableDeselect(it) }
        selected.clear()
    }

    /**
     * Set up a canvas link as a drop zone for cards. It must be a Sortable container so the link is a
     * valid drop target (Sortable then reports `event.to` = this link to the source column's onEnd) —
     * but its own onEnd never fires for a card dragged in from a column (Sortable dispatches onEnd on
     * the *source* list). So the actual move is handled in [handleDrop] (which detects a canvas-link
     * target); this onEnd is intentionally a no-op.
     */
    fun setupCanvasLinkDropZone(elementId: String, sourceCanvasName: CanvasName) {
        makeSortable(SortableConfig(
            elementId = ElementId(elementId),
            group = "cards",
            animation = 150,
            forceFallback = true,
            fallbackOnBody = true,
            fallbackTolerance = 4,
            emptyInsertThreshold = 12,   // small enough that stacked columns' accept-zones don't overlap (no top/bottom flicker)
            onEnd = { /* handled by the source column's onEnd → handleDrop; see kdoc */ }
        ))
    }

    // --- Wormhole portal: hover detection + lifecycle -----------------------------------------

    /** A drag started: record the dragged card's size, start tracking, install the pointer hit-test. */
    private fun beginPortalTracking(dragged: Element) {
        dragActive = true
        val r = dragged.getBoundingClientRect()
        if (r.width > 0) dragCardW = r.width
        if (r.height > 0) dragCardH = r.height
        installPortalHook()
    }

    /** Drag ended: stop tracking and fade the hover vortex (a drop-swallow flash plays on regardless). */
    private fun stopPortalTracking() {
        dragActive = false
        activeLink = null
        WormholePortal.hide()
    }

    /** Install (once) a capture-phase pointermove that lights up the vortex over a hovered link. */
    private fun installPortalHook() {
        if (portalHookInstalled) return
        portalHookInstalled = true
        document.asDynamic().addEventListener("pointermove", { event: dynamic -> onPortalPointerMove(event) }, true)
    }

    /**
     * While a card is being dragged, light the vortex over whichever canvas-link the pointer is within
     * (a small catch-margin past the chip), and clear it when the pointer leaves. Pure hit-testing —
     * independent of where Sortable has parked its ghost — so the visual is reliable and self-contained.
     */
    private fun onPortalPointerMove(event: dynamic) {
        if (!dragActive) return
        lastPointerX = event.clientX.unsafeCast<Double>()
        lastPointerY = event.clientY.unsafeCast<Double>()
        val zones = document.querySelectorAll(".canvas-drop-zone").asDynamic()
        val n = zones.length.unsafeCast<Int>()
        val margin = 16.0
        var hit: Element? = null
        var i = 0
        while (i < n) {
            val z = zones.item(i)
            val r = z.getBoundingClientRect()
            val left = r.left.unsafeCast<Double>() - margin
            val right = r.right.unsafeCast<Double>() + margin
            val top = r.top.unsafeCast<Double>() - margin
            val bottom = r.bottom.unsafeCast<Double>() + margin
            if (lastPointerX in left..right && lastPointerY in top..bottom) { hit = z.unsafeCast<Element>(); break }
            i++
        }
        if (hit !== activeLink) {
            activeLink = hit
            if (hit != null) WormholePortal.showOver(hit.unsafeCast<HTMLElement>()) else WormholePortal.hide()
        }
    }

    /**
     * Inject/refresh the drag styling from the active theme. The drop placeholder (ghostClass) is a
     * solid rectangle with its contents hidden; the floating mirror (dragClass) is opaque with a
     * solid background + shadow. Colors come from the typed theme (interpolated into the rule —
     * an injected stylesheet is inherently a string, but the source stays typed).
     */
    private fun ensureDragStyles() {
        val theme = ThemeManager.current
        // Placeholder background-image: "@bg" paints the page background fixed to the viewport, so the
        // preview cuts straight through the (translucent) column to the real background. A gradient value
        // paints it on the preview itself (e.g. a spotlight). "none" = the solid dragPlaceholder color.
        val phImg = if (theme.dragPlaceholderImage == "@bg") theme.appBgImage else theme.dragPlaceholderImage
        val phAttach = if (theme.dragPlaceholderImage == "@bg") "fixed" else "scroll"
        val css = """
            .tdz-card-ghost { background-color: ${theme.dragPlaceholder} !important; background-image: $phImg !important; background-attachment: $phAttach !important; background-size: cover !important; border-color: ${theme.dragPlaceholder} !important; border-radius: 0 !important; opacity: 1 !important; box-shadow: none !important; }
            .tdz-card-ghost * { visibility: hidden !important; }
            .tdz-card-drag { opacity: 1 !important; background-color: ${theme.dragMirrorBg} !important; border-radius: 0 !important; box-shadow: 5px 5px 0 ${theme.cardShadow} !important; }
            .tdz-card-selected { outline: 2px dashed ${theme.dragPlaceholder} !important; outline-offset: -2px; }
            /* Group drag: the mirror becomes a transparent container holding clones of the real cards
               (each clone keeps its own background/border/shadow), so it reads as the actual stack
               of cards moving. The placeholder is grown to the group height inline. */
            .tdz-card-drag-group { background-color: transparent !important; background-image: none !important; box-shadow: none !important; padding: 0 !important; }
            /* Each cloned card gets the opaque drag background (themes like rainbow use translucent card fills, which would show the page through the floating stack). */
            .tdz-card-drag-group .tdz-card { background-color: ${theme.dragMirrorBg} !important; background-image: none !important; }
            /* Canvas-link drop zone: Sortable parks the dragged card inside the inline link chip while
               hovering. Hide it so the small chip doesn't balloon — the wormhole vortex is the feedback. */
            .canvas-drop-zone .tdz-card-ghost { display: none !important; }
        """.trimIndent()
        val style = (getHtmlElement("tdz-drag-styles") as? HTMLStyleElement)
            ?: (document.createElement("style") as HTMLStyleElement).also {
                it.id = ElementId("tdz-drag-styles")
                document.head.appendChild(it)
            }
        style.textContent = css
    }

    private fun handleDrop(event: zoned.framework.libs.SortableDropEvent, canvasName: CanvasName) {
        // Dropped onto a canvas link → wormhole the card(s) into that canvas. Detected here (not in the
        // link zone's own onEnd) because Sortable fires onEnd on the *source* column, never the target.
        extractCanvasName(event.toId.asString())?.let { targetCanvas ->
            handleWormholeDrop(event, canvasName, targetCanvas)
            return
        }

        val fromColumnId = extractColumnId(event.fromId.asString())
        val toColumnId = extractColumnId(event.toId.asString())
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
        val header = parseCardId(event.itemId.asString())
        if (header != null) {
            val children = model.cards(canvasName, header.first).cardsUnderH1(header.second)
            // Sortable always drops the header element into the target column, so it is present in
            // newToCards; only relocate the children once we've found it (guards against losing
            // cards if that invariant ever fails to hold).
            val headerPos = newToCards.indexOfFirst {
                it.metadata.columnId == header.first && it.index == header.second
            }
            if (children.isNotEmpty() && headerPos >= 0) {
                val childIds = children.map { it.metadata.columnId to it.index }.toSet()
                newToCards.removeAll { (it.metadata.columnId to it.index) in childIds }
                newFromCards?.removeAll { (it.metadata.columnId to it.index) in childIds }
                // Re-find the header: removing children above it (same-column drag) shifts its index.
                val pos = newToCards.indexOfFirst {
                    it.metadata.columnId == header.first && it.index == header.second
                }
                newToCards.addAll(pos + 1, children)
            }
        }

        // saveCards rewrites each card's columnId to the target column, so a cross-column
        // move is handled the same as a reorder.
        model.saveCards(canvasName, toColumnId, newToCards)
        newFromCards?.let { model.saveCards(canvasName, fromColumnId, it) }

        // Drop done: drop the MultiDrag selection (the re-render below rebuilds the elements
        // anyway, but this keeps our tracking honest and avoids leaking selection state).
        clearSelection()

        // Re-render so the positional card ids reflect the new order.
        refreshColumns(canvasName, setOf(fromColumnId, toColumnId))
    }

    /** Map a column's card elements, in DOM order, back to their model [Card]s. */
    private fun cardsInDomOrder(container: Element, canvasName: CanvasName): List<Card> {
        val children = container.children
        val cards = mutableListOf<Card>()
        for (i in 0 until children.length) {
            val id = children[i]?.id?.asString() ?: continue
            val (column, index) = parseCardId(id) ?: continue
            cards += model.getCardByIndex(canvasName, column, index)
        }
        return cards
    }

    /** Parse a positional card element id ("card-{columnId}-{index}") into (columnId, index). */
    private fun parseCardId(id: String): Pair<Int, Int>? {
        val parts = id.split("-")
        if (parts.size != 3 || parts[0] != "card") return null
        val column = parts[1].toIntOrNull() ?: return null
        val index = parts[2].toIntOrNull() ?: return null
        return column to index
    }

    private fun extractColumnId(elementId: String): Int? {
        val parts = elementId.split("-")
        if (parts.size < 2 || parts[0] != "column") {
            return null
        }
        return parts[1].toIntOrNull()
    }

    private fun refreshColumns(canvasName: CanvasName, columnIds: Set<Int>) {
        eventBus.fire(RefreshColumns(canvasName, columnIds))
    }

    /**
     * Move the dragged card(s) into the canvas the dropped-on link points to, with the wormhole
     * "swallow" visual. The dragged element(s) are now sitting in the link drop zone (MultiDrag carries
     * a selected section there), so the set to move is read straight from the zone's DOM order.
     *
     * Per the design the user stays on the current canvas (no SwitchToCanvas) — the cards just vanish
     * into the portal.
     */
    private fun handleWormholeDrop(
        event: zoned.framework.libs.SortableDropEvent,
        sourceCanvas: CanvasName,
        targetCanvas: CanvasName,
    ) {
        val dragged = cardsInDomOrder(event.to, sourceCanvas)
        if (dragged.isEmpty()) {
            clearSelection()
            return
        }

        // Cosmetic: flash the vortex and spiral a clone of the primary card into its core. Started
        // before the model move/refresh below (which detaches the real element); the clone is independent.
        (getHtmlElement(event.toId.asString()) as? HTMLElement)?.let { linkEl ->
            val cx = if (lastPointerX != 0.0) lastPointerX else linkEl.getBoundingClientRect().let { it.left + it.width / 2 }
            val cy = if (lastPointerY != 0.0) lastPointerY else linkEl.getBoundingClientRect().let { it.top + it.height / 2 }
            (event.item as? HTMLElement)?.let { card ->
                WormholePortal.swallow(linkEl, card, cx, cy, dragCardW, dragCardH)
            }
        }

        // Move every dragged card to the target canvas. Remove highest source index first so each
        // removal (moveCardToCanvas removes by index) doesn't shift the indices of cards not yet moved.
        dragged.sortedByDescending { it.index }.forEach { model.moveCardToCanvas(it, sourceCanvas, targetCanvas) }

        clearSelection()
        // Sortable parked the dragged element(s) inside the link chip. Remove them so they don't linger
        // hidden there — the source columns re-render below, but the link's own column may not be in the
        // refreshed set.
        removeParkedCards(event.to)
        refreshColumns(sourceCanvas, dragged.map { it.metadata.columnId }.toSet())
    }

    /** Remove any dragged card elements Sortable left inside [container] (a canvas-link drop zone). */
    private fun removeParkedCards(container: Element) {
        val parked = container.querySelectorAll("[id^='card-']").asDynamic()
        val n = parked.length.unsafeCast<Int>()
        var i = 0
        while (i < n) {
            parked.item(i)?.remove()   // parked is already dynamic — no .asDynamic() on its items
            i++
        }
    }

    private fun extractCanvasName(elementId: String): CanvasName? {
        // Format: "canvas-link-{canvasName}"
        val prefix = "canvas-link-"
        if (!elementId.startsWith(prefix)) {
            return null
        }
        return CanvasName(elementId.removePrefix(prefix))
    }
}
