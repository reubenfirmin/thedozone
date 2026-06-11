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
    const val CARD_HEADER = "tdz-card-header"
    const val CARD_BODY = "tdz-card-body"
    const val CARD_FLOURISH = "tdz-card-flourish"
    const val LOOSE_SEG = "tdz-loose-seg"

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
