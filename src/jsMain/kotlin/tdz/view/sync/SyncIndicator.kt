package tdz.view.sync

import kotlinx.css.*
import kotlinx.html.div
import tdz.controller.events.EventBus
import tdz.controller.events.SyncStatusChanged
import tdz.sync.SyncState
import tdz.sync.SyncStateStore
import tdz.sync.locked
import tdz.view.Zones
import web.html.HTMLElement
import zoned.framework.dom.Ref
import zoned.framework.dom.onClick
import zoned.framework.dom.onMount
import zoned.framework.dom.ref
import zoned.framework.interop.addToBody
import zoned.framework.interop.css
import zoned.framework.routing.Zone.Companion.zone

/**
 * Persistent corner badge + a blocking overlay shown while the state is `locked`. Both are
 * body-level; they subscribe to [SyncStatusChanged] via the component-scoped, auto-pruning
 * `eventBus.on` so they update and tear down with the DOM node.
 */
class SyncIndicator(
    private val eventBus: EventBus,
    private val openModal: () -> Unit,
) {

    private fun label(state: SyncState): String = when (state) {
        SyncState.NotConfigured -> ""
        SyncState.Connecting -> "Connecting…"
        SyncState.Reconciling -> "Syncing…"
        SyncState.Synced -> "Synced ✓"
        SyncState.Syncing -> "Syncing…"
        SyncState.Offline -> "Offline"
        SyncState.NeedsReconnect -> "Reconnect"
        is SyncState.Error -> "Sync error"
    }

    /**
     * Mount once. Canvas refreshes swap only the canvas zone (never the body), so the badge and
     * overlay — body-level siblings of that zone — live untouched for the app's lifetime.
     */
    fun attach() {
        mount()
    }

    /** Mount badge + overlay (idempotent: removes any previous instances first). */
    fun mount() {
        removeExisting()
        renderBadge()
        renderOverlay()
        applyState(SyncStateStore.current)
    }

    private fun renderBadge() {
        val r = Ref<HTMLElement>()
        addToBody {
            div {
                ref(r)
                zone(Zones.SYNC_BADGE)
                css {
                    position = Position.fixed
                    right = 12.px
                    bottom = 12.px
                    zIndex = 9000
                    padding = Padding(vertical = 4.px, horizontal = 10.px)
                    backgroundColor = SyncColors.badgeBg
                    color = SyncColors.badgeFg
                    border = Border(1.px, BorderStyle.solid, SyncColors.border)
                    borderRadius = 4.px
                    cursor = Cursor.pointer
                    fontSize = 12.px
                }
                onClick { openModal() }
                onMount {
                    val el = r.element
                    with(eventBus) { el.on<SyncStatusChanged> { applyState(it.state) } }
                }
            }
        }
    }

    private fun renderOverlay() {
        val r = Ref<HTMLElement>()
        addToBody {
            div {
                ref(r)
                zone(Zones.SYNC_OVERLAY)
                css {
                    position = Position.fixed
                    top = 0.px; left = 0.px; right = 0.px; bottom = 0.px
                    zIndex = 8999
                    display = Display.none
                    alignItems = Align.center
                    justifyContent = JustifyContent.center
                    backgroundColor = SyncColors.modalOverlay
                    color = SyncColors.overlayFg
                    fontSize = 18.px
                }
                +"⟳ Syncing…"
                onMount {
                    val el = r.element
                    with(eventBus) { el.on<SyncStatusChanged> { applyState(it.state) } }
                }
            }
        }
    }

    private fun applyState(state: SyncState) {
        Zones.SYNC_BADGE.resolve()?.let { badge ->
            val text = label(state)
            badge.textContent = text
            badge.style.display = if (text.isEmpty()) "none" else "block"
        }
        Zones.SYNC_OVERLAY.resolve()?.let { overlay ->
            overlay.style.display = if (state.locked) "flex" else "none"
        }
    }

    private fun removeExisting() {
        Zones.SYNC_BADGE.resolve()?.remove()
        Zones.SYNC_OVERLAY.resolve()?.remove()
    }
}
