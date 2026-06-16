package tdz.view

/**
 * The CSS class names shared across files — DSL class attributes, injected rule selectors, and
 * querySelector calls reference these constants so the compiler tracks every use (the companion to
 * [DomIds], which does the same for element ids). Classes private to a single component (the
 * wormhole portal's internals, the intro card) stay local to it.
 */
object CssClasses {
    // Card chrome ([CardRenderer.cardClasses] composes these as modifiers over CARD).
    const val CARD = "tdz-card"
    const val CARD_ELEV = "tdz-card-elev"
    const val CARD_CHROMELESS = "tdz-card-chromeless"
    const val CARD_STRIP = "tdz-card-strip"
    const val CARD_FRAMED = "tdz-card-framed"
    const val CARD_FRAME_TITLE = "tdz-card-frame-title"
    const val CARD_LOOSE = "tdz-card-loose"
    const val CARD_IMAGE = "tdz-card-image"
    const val CARD_HEADER = "tdz-card-header"
    const val CARD_BODY = "tdz-card-body"
    const val CARD_FLOURISH = "tdz-card-flourish"
    const val LOOSE_SEG = "tdz-loose-seg"

    // Flip cards (a card with `//` face boundaries). FLIP marks the CARD element, which turns in 3D
    // (perspective lives in the card's own transform, so a scrolling column doesn't flatten it). The
    // front faces the viewer; the back is pre-rotated 180° (reads forward once turned, overlaid so the
    // card's height can animate from one side to the next); other faces are hidden.
    const val FLIP = "tdz-flip"
    const val FLIP_BTN = "tdz-flip-btn"
    const val FLIP_SIDE = "tdz-flip-side"        // base face (backface hidden, carries the padding)
    const val FLIP_FRONT = "tdz-flip-front"      // the face facing the viewer (in flow, sizes the card)
    const val FLIP_BACKFACE = "tdz-flip-backface" // the incoming face (overlaid, rotateY 180)
    const val FLIP_HIDDEN = "tdz-flip-hidden"    // any other face: display:none
    const val FLIP_TURN = "tdz-flip-turn"        // the card mid-turn: rotated 180°
    const val FLIP_RING = "tdz-flip-ring"        // transient outline while turning, when the theme card has no border

    // Card content.
    const val TASK = "tdz-task"
    const val MARKER = "tdz-marker"
    const val MARKER_DONE = "tdz-marker-done"
    const val CALLOUT = "tdz-callout"
    const val H1 = "tdz-h1"
    const val H2 = "tdz-h2"
    const val H3 = "tdz-h3"
    const val H4 = "tdz-h4"

    // Canvas links (chip + its Sortable-independent drop hit-zone).
    const val CANVAS_LINK = "tdz-canvas-link"
    const val DROP_ZONE = "canvas-drop-zone"

    // Drag visuals (Sortable's ghost/chosen/drag/selected hooks + the group-drag mirror).
    const val CARD_GHOST = "tdz-card-ghost"
    const val CARD_CHOSEN = "tdz-card-chosen"
    const val CARD_DRAG = "tdz-card-drag"
    const val CARD_SELECTED = "tdz-card-selected"
    const val CARD_DRAG_GROUP = "tdz-card-drag-group"

    /** The class as a CSS selector: `.tdz-card`. */
    fun sel(className: String) = ".$className"
}
