package tdz.view

import zoned.framework.routing.Zone

/**
 * Compile-time registry of the app's named DOM zones — every body-level mount point in one place.
 * Components mark their element with `zone(Zones.X)` when building it and update it with
 * `Zones.X.swap { }` / `Zones.X.resolve()`, instead of scattering string ids and tearing down the
 * body. Body-level layers are siblings: swapping one zone never disturbs the others.
 */
object Zones {

    /** The canvas board (columns + cards). Swapped on navigation and [tdz.controller.events.RefreshCanvas]. */
    val CANVAS = Zone("app-container")

    /** Full-screen overlay layer for the help/settings routes (see [Overlay]). */
    val OVERLAY = Zone("overlay")

    /** First-run welcome card over a pristine root canvas (see [IntroCard]). */
    val INTRO = Zone("tdz-intro")

    /** The examples wizard opened from the welcome card (see [ExamplesWizard]). */
    val EXAMPLES_WIZARD = Zone("tdz-examples-wizard")

    /** The canvas-link drop visual (see [WormholePortal]). */
    val WORMHOLE = Zone("tdz-wormhole")

    /** Decorative focused-column sprite (see [ColumnFocusSprite]). */
    val FOCUS_SPRITE = Zone("tdz-focus-sprite")

    /** Sync corner badge (see [tdz.view.sync.SyncIndicator]). */
    val SYNC_BADGE = Zone("tdz-sync-badge")

    /** Blocking overlay shown while sync holds the canvas (see [tdz.view.sync.SyncIndicator]). */
    val SYNC_OVERLAY = Zone("tdz-sync-overlay")
}
