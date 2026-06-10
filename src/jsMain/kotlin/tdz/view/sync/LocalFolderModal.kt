package tdz.view.sync

import kotlinx.css.*
import kotlinx.html.div
import tdz.controller.events.EventBus
import tdz.controller.events.SyncConflict
import tdz.controller.events.SyncStatusChanged
import tdz.model.Model
import tdz.sync.SyncController
import tdz.sync.SyncState
import tdz.sync.SyncStateStore
import tdz.view.ZLayer
import tdz.view.modalButton
import tdz.view.theme.ThemeManager
import web.html.HTMLElement
import zoned.framework.dom.Ref
import zoned.framework.dom.onClick
import zoned.framework.dom.onMount
import zoned.framework.dom.ref
import zoned.framework.interop.addToBody
import zoned.framework.interop.css

/**
 * Body-level modal for Local Folder Sync (Chromium File System Access backend). Mirrors [SyncModal]'s
 * rendering model. Reads the active config from [model], delegates to [controller], and reflects live
 * [SyncState] (including [SyncState.NeedsReconnect]) via a component-scoped subscription. Connect and
 * reconnect both run from a real button click so the underlying File System Access call keeps its user
 * gesture.
 */
class LocalFolderModal(
    private val model: Model,
    private val eventBus: EventBus,
    private val controller: SyncController,
    private val onDismiss: (() -> Unit)? = null,
) {
    private var rootEl: HTMLElement? = null

    init {
        eventBus.on<SyncStatusChanged> { if (rootEl != null) render() }
    }

    fun show() = render()

    fun hide() { rootEl?.remove(); rootEl = null }

    private fun render() {
        hide()
        val cfg = model.readSyncConfig()
        val state = SyncStateStore.current
        val needsReconnect = state == SyncState.NeedsReconnect
        val connected = cfg.localConnected
        val r = Ref<HTMLElement>()
        val statusRef = Ref<HTMLElement>()
        addToBody {
            div {   // scrim
                ref(r)
                css {
                    position = Position.fixed
                    top = 0.px; left = 0.px; right = 0.px; bottom = 0.px
                    zIndex = ZLayer.MODAL
                    display = Display.flex
                    alignItems = Align.center
                    justifyContent = JustifyContent.center
                    backgroundColor = SyncColors.modalOverlay
                }
                onClick { e -> if (e.target == r.element) (onDismiss ?: { hide() })() }
                div {   // dialog
                    css {
                        minWidth = 360.px
                        padding = Padding(20.px)
                        backgroundColor = ThemeManager.current.menuBg
                        color = ThemeManager.current.menuFg
                        border = Border(1.px, BorderStyle.solid, ThemeManager.current.menuBorder)
                        borderRadius = 6.px
                    }
                    div { css { fontSize = 18.px; marginBottom = 12.px }; +"Local Folder Sync" }

                    div {
                        css { marginTop = 8.px; marginBottom = 8.px; fontSize = 13.px }
                        +(if (connected || needsReconnect)
                            "Folder: ${cfg.localFolderName ?: "(folder)"}"
                          else "Sync this device's canvases to a folder on your disk.")
                    }
                    div { ref(statusRef); css { marginBottom = 12.px; fontSize = 12.px }
                        +stateText(state) }

                    div {
                        css { display = Display.flex; flexDirection = FlexDirection.row }
                        when {
                            needsReconnect -> {
                                button("Reconnect", primary = true) { controller.reconnectLocalFolder() }
                                button("Disconnect") { controller.disconnect() }
                            }
                            connected -> {
                                button("Sync now", primary = true) { controller.syncNow() }
                                button("Disconnect") { controller.disconnect() }
                            }
                            else -> button("Choose folder…", primary = true) { controller.connectLocalFolder() }
                        }
                    }

                    onMount {
                        rootEl = r.element
                        val s = statusRef.element
                        with(eventBus) {
                            s.on<SyncStatusChanged> { s.textContent = stateText(it.state) }
                            s.on<SyncConflict> { s.textContent = "Conflict on ${it.path}: ${it.winner} kept" }
                        }
                    }
                }
            }
        }
    }

    private fun stateText(state: SyncState): String = when (state) {
        SyncState.NotConfigured -> "Not connected"
        SyncState.Connecting -> "Opening folder…"
        SyncState.Reconciling -> "Reconciling…"
        SyncState.Synced -> "Up to date"
        SyncState.Syncing -> "Syncing…"
        SyncState.Offline -> "Offline — changes will sync when online"
        SyncState.NeedsReconnect -> "Folder access paused — click Reconnect to resume"
        is SyncState.Error -> "Error: ${state.message}"
    }

    // --- button helper (kotlinx.html receiver), copied from SyncModal's style ---
    private fun kotlinx.html.FlowContent.button(
        text: String, primary: Boolean = false, enabled: Boolean = true, onPress: () -> Unit,
    ) = modalButton(text, primary, enabled, onPress)
}
