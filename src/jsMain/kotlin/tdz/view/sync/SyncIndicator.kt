package tdz.view.sync

import kotlinx.browser.window
import kotlinx.css.*
import kotlinx.html.div
import tdz.controller.events.EventBus
import tdz.controller.events.RefreshCanvas
import tdz.controller.events.SyncStatusChanged
import tdz.sync.SyncState
import tdz.sync.SyncStateStore
import tdz.sync.locked
import web.dom.ElementId
import web.html.HTMLElement
import zoned.framework.dom.Ref
import zoned.framework.dom.onClick
import zoned.framework.dom.onMount
import zoned.framework.dom.ref
import zoned.framework.interop.addToBody
import zoned.framework.interop.css

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
        SyncState.NeedsReconnect -> "Reconnect folder"
        is SyncState.Error -> "Sync error"
    }

    /**
     * Mount once and keep alive across re-renders. `RefreshCanvas` clears the body, so re-mount on
     * the next tick (after Index's render handler, whose order vs ours is unspecified).
     */
    fun attach() {
        mount()
        // global subscription (indicator lives for the app's lifetime)
        eventBus.on<RefreshCanvas> { window.setTimeout({ mount() }, 0) }
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
                attributes["id"] = BADGE_ID
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
                attributes["id"] = OVERLAY_ID
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
        (web.dom.document.getElementById(ElementId(BADGE_ID)) as? HTMLElement)?.let { badge ->
            val text = label(state)
            badge.textContent = text
            badge.style.display = if (text.isEmpty()) "none" else "block"
        }
        (web.dom.document.getElementById(ElementId(OVERLAY_ID)) as? HTMLElement)?.let { overlay ->
            overlay.style.display = if (state.locked) "flex" else "none"
        }
    }

    private fun removeExisting() {
        (web.dom.document.getElementById(ElementId(BADGE_ID)))?.remove()
        (web.dom.document.getElementById(ElementId(OVERLAY_ID)))?.remove()
    }

    companion object {
        private const val BADGE_ID = "tdz-sync-badge"
        private const val OVERLAY_ID = "tdz-sync-overlay"
    }
}
