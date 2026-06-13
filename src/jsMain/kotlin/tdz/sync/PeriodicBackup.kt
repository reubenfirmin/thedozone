package tdz.sync

import kotlinx.browser.window
import tdz.controller.events.ColumnsChanged
import tdz.controller.events.Event
import tdz.controller.events.RefreshCanvas
import zoned.framework.events.EventBus

/**
 * Keeps ONE rolling restore point: every [INTERVAL_MS] (30 min), if any data changed since the last
 * write, the periodic backup slot is refreshed ([LocalBackup.autoSnapshot]). Pre-sync snapshots
 * guard against bad pulls; this guards against the user's own destructive actions (a misfired
 * Clear Done, a fat-fingered edit) with a bounded blast radius — at most the last 30 minutes.
 */
object PeriodicBackup {

    const val INTERVAL_MS = 30 * 60_000
    private var dirty = false
    private var timerStarted = false

    fun init(eventBus: EventBus<Event>) {
        // Any model fact marks the slot stale — including remote pulls (the backup mirrors what's
        // on screen, wherever it came from) and structural canvas changes.
        eventBus.on<ColumnsChanged> { dirty = true }
        eventBus.on<RefreshCanvas> { e -> if (e.structural) dirty = true }
        // Feature's first run on this profile: give the user a restore point right away
        // instead of 30 minutes in.
        if (LocalBackup.autoTimestamp() == null) LocalBackup.autoSnapshot()
        if (timerStarted) return   // the singleton timer survives re-inits
        timerStarted = true
        window.setInterval({ tick() }, INTERVAL_MS)
    }

    /** One timer beat: refresh the slot only when something actually changed. Returns whether it wrote. */
    internal fun tick(): Boolean {
        if (!dirty || !LocalBackup.autoSnapshot()) return false
        dirty = false
        return true
    }
}
