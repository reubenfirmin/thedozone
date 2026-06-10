package tdz.view.sync

import kotlinx.browser.window
import kotlinx.css.*
import kotlinx.css.properties.*
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.input
import tdz.controller.events.EventBus
import tdz.controller.events.SyncConflict
import tdz.controller.events.SyncStatusChanged
import tdz.model.Model
import tdz.sync.SyncPaths
import tdz.sync.SyncController
import tdz.sync.SyncProvider
import tdz.sync.SyncState
import tdz.sync.SyncStateStore
import tdz.view.ZLayer
import tdz.view.modalButton
import tdz.view.theme.ThemeManager
import web.dom.ElementId
import web.dom.document
import web.html.HTMLElement
import web.html.HTMLInputElement
import zoned.framework.dom.Ref
import zoned.framework.dom.onClick
import zoned.framework.dom.onMount
import zoned.framework.dom.ref
import zoned.framework.interop.addToBody
import zoned.framework.interop.css

/**
 * Body-level modal controlling cloud sync setup for whichever [SyncProvider] the user picks. Follows
 * the ContextMenu rendering model (addToBody + Ref + onMount + typed css + theme tokens). Reads current
 * config from [model], delegates actions to [controller], and reflects live state via component-scoped
 * subscriptions.
 *
 * While disconnected the user chooses a provider (Dropbox / Google Drive) and a folder. Buttons are
 * connection-aware: **Connect** when disconnected; **Sync now / Change folder / Disconnect** when
 * connected. The sync folder and provider are read-only once connected (they're properties of the
 * sync) — "Change folder" re-syncs against the new root; switching provider requires Disconnect first.
 */
class SyncModal(
    private val model: Model,
    private val eventBus: EventBus,
    private val controller: SyncController,
    private val onDismiss: (() -> Unit)? = null,
) {
    private var rootEl: HTMLElement? = null
    private var shownConnected = false
    private var editingFolder = false
    private var selectedProvider: SyncProvider =
        model.readSyncConfig().provider.coerceCloud()

    init {
        eventBus.on<SyncStatusChanged> {
            if (rootEl != null && connected() != shownConnected) { editingFolder = false; render() }
        }
    }

    private fun connected(): Boolean = model.readSyncConfig().cloudConnected

    fun show() {
        editingFolder = false
        selectedProvider = model.readSyncConfig().provider.coerceCloud()
        render()
    }

    fun hide() { rootEl?.remove(); rootEl = null }

    private fun render() {
        // A rebuild (provider click, connect/disconnect flip) replaces the folder input; carry the
        // user's in-progress text across, falling back to the stored folder on first paint.
        val pendingFolder = (document.getElementById(ElementId(ROOT_ID)) as? HTMLInputElement)?.value
        hide()
        val cfg = model.readSyncConfig()
        shownConnected = cfg.cloudConnected
        val connecting = SyncStateStore.current == SyncState.Connecting
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
                    div { css { fontSize = 18.px; marginBottom = 12.px }; +"Cloud Sync" }

                    // Provider chooser: only before connecting (provider is fixed for the life of a sync).
                    if (!shownConnected) {
                        providerChooser(connecting)
                        if (selectedProvider == SyncProvider.GOOGLE_DRIVE) {
                            div {
                                css {
                                    marginTop = (-2).px; marginBottom = 10.px
                                    fontSize = 12.px; lineHeight = LineHeight("1.4")
                                    opacity = 0.85; maxWidth = 340.px
                                }
                                +("Heads up: Google signs you out periodically, so Drive needs an " +
                                  "occasional Reconnect. Dropbox or a local folder + Syncthing stay " +
                                  "connected without that.")
                            }
                        }
                    }

                    // Folder: editable before connect or while changing it; read-only otherwise.
                    if (!shownConnected || editingFolder) {
                        field(folderHint(), ROOT_ID, pendingFolder ?: cfg.rootFolder)
                    } else {
                        div {
                            css { marginBottom = 8.px; fontSize = 13.px }
                            +"Folder: ${folderLabel(cfg.rootFolder)}"
                        }
                    }

                    div {
                        css { marginTop = 8.px; marginBottom = 8.px }
                        +(if (shownConnected)
                            "Connected to ${cfg.provider.displayName}: ${cfg.accountEmail ?: cfg.provider.displayName}"
                          else "Not connected")
                    }
                    div { ref(statusRef); css { marginBottom = 12.px; fontSize = 12.px }
                        +stateText(SyncStateStore.current) }

                    // Google's GIS token model has no refresh token, so its session lapses periodically.
                    // That's expected — frame it as a calm reconnect, not an error, and point at the
                    // providers that DON'T need this dance.
                    if (SyncStateStore.current == SyncState.NeedsReconnect &&
                        cfg.provider == SyncProvider.GOOGLE_DRIVE) {
                        div {
                            css {
                                marginBottom = 12.px; fontSize = 12.px; lineHeight = LineHeight("1.4")
                                opacity = 0.85; maxWidth = 340.px
                            }
                            +("Google signs you out periodically — that's expected, not an error. Just " +
                              "click Reconnect to resume. Dropbox or a local folder + Syncthing stay " +
                              "connected without these periodic reconnects.")
                        }
                    }

                    div {
                        css { display = Display.flex; flexDirection = FlexDirection.row }
                        when {
                            !shownConnected -> button(
                                if (connecting) "Connecting…" else "Connect",
                                primary = true, enabled = !connecting,
                            ) {
                                controller.setProvider(selectedProvider)
                                controller.setRootFolder(inputVal(ROOT_ID))
                                controller.connect()
                            }

                            editingFolder -> {
                                button("Use folder", primary = true) {
                                    controller.changeRootFolder(inputVal(ROOT_ID))
                                    editingFolder = false; render()
                                }
                                button("Cancel") { editingFolder = false; render() }
                            }

                            else -> {
                                // Reconnect is the primary action whenever auth lapsed: an expected Google
                                // session timeout (NeedsReconnect) or a genuine Error (e.g. a revoked
                                // Dropbox refresh token). The user gesture drives the consent popup.
                                val needsAuth = SyncStateStore.current is SyncState.Error ||
                                    SyncStateStore.current == SyncState.NeedsReconnect
                                if (needsAuth) {
                                    button("Reconnect", primary = true) { controller.connect() }
                                }
                                button("Sync now", primary = !needsAuth) {
                                    controller.syncNow()
                                }
                                button("Change folder") { editingFolder = true; render() }
                                button("Disconnect") { controller.disconnect() }
                                button("Restore last backup") {
                                    if (controller.restoreLastBackup())
                                        window.alert("Restored the most recent pre-sync backup.")
                                    else
                                        window.alert("No backup available yet.")
                                }
                            }
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

    private fun FlowContent.providerChooser(connecting: Boolean) {
        div {
            css { marginBottom = 10.px; display = Display.flex; flexDirection = FlexDirection.row }
            SyncProvider.values().filter { it != SyncProvider.LOCAL_FOLDER }.forEach { provider ->
                val chosen = provider == selectedProvider
                div {
                    css {
                        flexGrow = 1.0
                        padding = Padding(vertical = 8.px, horizontal = 12.px)
                        marginRight = 8.px
                        cursor = if (connecting) Cursor.default else Cursor.pointer
                        if (connecting) opacity = 0.5
                        borderRadius = 4.px
                        fontSize = 13.px
                        fontWeight = if (chosen) FontWeight.w600 else FontWeight.normal
                        textAlign = TextAlign.center
                        userSelect = UserSelect.none
                        backgroundColor = if (chosen) SyncColors.primaryBg else SyncColors.buttonBg
                        color = if (chosen) SyncColors.primaryFg else SyncColors.buttonFg
                        border = Border(
                            1.px, BorderStyle.solid,
                            if (chosen) SyncColors.primaryBg else SyncColors.buttonBorder,
                        )
                    }
                    if (!connecting) onClick { selectedProvider = provider; render() }
                    +provider.displayName
                }
            }
        }
    }

    private fun folderHint(): String = when (selectedProvider) {
        SyncProvider.DROPBOX -> "Sync folder (blank = app root)"
        SyncProvider.GOOGLE_DRIVE -> "Sync folder (blank = \"thedozone2\")"
        SyncProvider.LOCAL_FOLDER -> ""   // never shown: local is excluded from this modal's chooser
    }

    private fun folderLabel(raw: String): String =
        SyncPaths.rootPrefix(raw).ifEmpty { "(provider root)" }

    private fun stateText(state: SyncState): String = when (state) {
        SyncState.NotConfigured -> "Not connected"
        SyncState.Connecting -> "Connecting…"
        SyncState.Reconciling -> "Reconciling…"
        SyncState.Synced -> "Up to date"
        SyncState.Syncing -> "Syncing…"
        SyncState.Offline -> "Offline — changes will sync when online"
        SyncState.NeedsReconnect ->
            if (model.readSyncConfig().provider == SyncProvider.GOOGLE_DRIVE) "Reconnection needed"
            else "Reconnect to resume"
        is SyncState.Error -> "Error: ${state.message}"
    }

    private fun inputVal(id: String): String =
        (document.getElementById(ElementId(id)) as? HTMLInputElement)?.value ?: ""

    // --- small DSL helpers (kotlinx.html receiver) ---

    private fun FlowContent.field(labelText: String, inputId: String, value: String) {
        div {
            css { marginBottom = 8.px; display = Display.flex; flexDirection = FlexDirection.column }
            div { css { fontSize = 12.px; marginBottom = 2.px }; +labelText }
            input(type = InputType.text) {
                id = inputId
                attributes["value"] = value
                css {
                    padding = Padding(6.px)
                    backgroundColor = SyncColors.inputBg
                    color = ThemeManager.current.menuFg
                    border = Border(1.px, BorderStyle.solid, SyncColors.inputBorder)
                    borderRadius = 3.px
                }
            }
        }
    }

    private fun FlowContent.button(
        text: String, primary: Boolean = false, enabled: Boolean = true, onPress: () -> Unit,
    ) = modalButton(text, primary, enabled, onPress)

    companion object {
        private const val ROOT_ID = "tdz-sync-root"
    }
}

/** Cloud Sync modal only deals with cloud providers; map an active LOCAL_FOLDER to a sane default. */
private fun SyncProvider.coerceCloud(): SyncProvider =
    if (this == SyncProvider.LOCAL_FOLDER) SyncProvider.DROPBOX else this
