package tdz.view

/**
 * The app's stacking order, in one place. Every `z-index` in the codebase references a layer here —
 * a new layer slots in by value, and collisions (the old submenu/overlay tie at 10000) are
 * impossible to reintroduce silently.
 */
object ZLayer {
    /** Decorative focused-column sprite — above columns, below all chrome. */
    const val FOCUS_SPRITE = 60

    /** First-run welcome card — over the canvas, under menus and overlays. */
    const val WELCOME_CARD = 500

    /** Blocking scrim while sync holds the canvas (display:none unless locked). */
    const val SYNC_OVERLAY = 8_999

    /** Sync status corner badge. */
    const val SYNC_BADGE = 9_000

    /** Window Controls Overlay titlebar strip (installed PWA on desktop Chromium). */
    const val TITLEBAR = 9_500

    /** Floating context menu. */
    const val CONTEXT_MENU = 9_999

    /** Submenu flyouts (above their parent menu). */
    const val SUBMENU = 10_000

    /** The help/settings overlay zone (mutually exclusive with menus in practice, but distinct). */
    const val OVERLAY = 10_010

    /** Body-level modals (sync setup, column properties) — above the overlay. */
    const val MODAL = 10_020

    /** Examples wizard (opened from the welcome card). */
    const val EXAMPLES_WIZARD = 10_050

    /** Wormhole drop visual — above Sortable's drag mirror (~100000). */
    const val WORMHOLE = 1_000_000

    /** The card clone swallowed by the wormhole — just above the portal. */
    const val WORMHOLE_CARD = 1_000_001
}
