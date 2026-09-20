package tdz.controller

import web.dom.Element
import web.dom.document
import web.events.AddEventListenerOptions
import web.events.addEventListener
import web.html.HTMLElement
import web.pointer.POINTER_MOVE
import web.pointer.PointerEvent
import tdz.view.CssClasses
import tdz.view.DomIds
import tdz.view.WormholePortal

/**
 * The wormhole-portal hover/hit-test state machine, lifted out of [DragDrop] so the drag controller
 * stays a lean drop→event coordinator. Canvas-link chips are NOT Sortable containers — Sortable
 * parking its hidden ghost in the tiny chip collapsed the column gap and made the link card "dodge"
 * the pointer. Instead this pointer hit-test is the single source of truth: it lights the portal AND
 * routes the drop ([DragDrop.handleDrop] checks [activeLink]), so the visual and the behavior cannot
 * disagree.
 */
class WormholePortalTracker {

    /** True while a card drag is in flight; gates the global pointermove hit-test. */
    private var dragActive = false
    /** The document pointermove hit-test is global; install it exactly once. */
    private var portalHookInstalled = false
    /** The canvas-link drop zone currently under the pointer (so we only re-show on change). */
    var activeLink: Element? = null
        private set
    /** The chip elements, snapshotted at drag start: columns never re-render mid-drag, so a
     *  per-pointermove querySelectorAll was pure overhead. Rects are still read live (chips shift
     *  with column reflow); an element that leaves the DOM mid-drag just stops participating. */
    private var dropZones: List<Element> = emptyList()
    /** Last pointer position + the dragged card's size, captured for the swallow animation. */
    var lastPointerX = 0.0
        private set
    var lastPointerY = 0.0
        private set
    var dragCardW = 160.0
        private set
    var dragCardH = 80.0
        private set

    /** Open the portal when the pointer comes this close to a chip. */
    private val enterMargin = 16.0
    /** Once open, keep it until the pointer is this far away — a relocation animation already in
     *  flight when the freeze engages can still slide the chip a little past [enterMargin], and
     *  that drift must not flicker the portal off mid-aim. */
    private val releaseMargin = 48.0

    /**
     * A drag started: record the dragged card's size, snapshot the candidate chips, install the
     * pointer hit-test. [selected] is the MultiDrag group (so a group member's own chip is excluded).
     */
    fun begin(dragged: Element, selected: List<Element>) {
        dragActive = true
        val r = dragged.getBoundingClientRect()
        if (r.width > 0) dragCardW = r.width
        if (r.height > 0) dragCardH = r.height
        dropZones = document.querySelectorAll(".${CssClasses.DROP_ZONE}").let { zones ->
            (0 until zones.length).mapNotNull { zones[it] }
        }.filterNot { zone ->
            // Wormhole targets must be REAL in-board chips. Exclude:
            //  (a) any chip not inside a column — Sortable's floating drag MIRROR is a clone of the
            //      card (with a copy of the chip) appended to <body>, and it follows the pointer, so
            //      it would otherwise light its own wormhole (intermittently, by clone timing); and
            //  (b) the dragged card's own chip (or a multi-drag group member's), so a card can't fall
            //      into itself.
            val card = zone.closest(DomIds.CARD_SELECTOR)
            zone.closest(DomIds.COLUMN_SELECTOR) == null || card === dragged || selected.any { it === card }
        }
        installPortalHook()
    }

    /** Drag ended: stop tracking and fade the hover vortex (a drop-swallow flash plays on regardless). */
    fun stop() {
        dragActive = false
        activeLink = null
        dropZones = emptyList()
        WormholePortal.hide()
    }

    /**
     * Sortable's onMove veto for every column: placeholder relocations are allowed only while no
     * wormhole is open. The moment the portal lights up, the drop is routed to the linked canvas
     * ([DragDrop.handleDrop]), so further placeholder shuffling is pointless — and actively harmful:
     * each relocation reflows the column and shifts the link card out from under the pointer (the
     * "dodge"), closing the very portal the user is aiming at. Frozen = nothing moves = stable aim.
     */
    fun placeholderMovesAllowed(): Boolean = activeLink == null

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
}
