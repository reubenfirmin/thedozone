package tdz.view.sync

import kotlinx.browser.window
import kotlinx.css.*
import kotlinx.css.properties.*
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.input
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.SyncConflict
import tdz.controller.events.SyncStatusChanged
import tdz.model.Model
import tdz.model.localstorage.SyncConfigDoc
import tdz.sync.SyncPaths
import tdz.sync.SyncController
import tdz.sync.SyncProvider
import tdz.sync.SyncState
import tdz.sync.SyncStateStore
import tdz.sync.locked
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
    private val eventBus: EventBus<Event>,
    private val controller: SyncController,
    private val onDismiss: (() -> Unit)? = null,
) {
    private var rootEl: HTMLElement? = null
    private var shownConnected = false
    private var shownNeedsAuth = false
    private var shownChoosing = false
    private var shownBusy = false
    private var editingFolder = false
    private var selectedProvider: SyncProvider =
        model.readSyncConfig().provider.coerceCloud()

    init {
        // The status LINE updates live (statusRef below); the BUTTON ROW must re-render whenever
        // either input it depends on changes: connectedness, or auth lapsing (NeedsReconnect/Error
        // swaps "Sync now" for a primary "Reconnect").
        eventBus.on<SyncStatusChanged> {
            if (rootEl != null && (connected() != shownConnected || needsAuth() != shownNeedsAuth ||
                    choosing() != shownChoosing || busy() != shownBusy)) {
                editingFolder = false
                render()
            }
        }
    }

    private fun needsAuth(): Boolean =
        SyncStateStore.current is SyncState.Error || SyncStateStore.current == SyncState.NeedsReconnect

    private fun choosing(): Boolean = SyncStateStore.current == SyncState.ChoosingDirection

    /** Connecting / Reconciling: work in flight, so the whole button row is inert. */
    private fun busy(): Boolean = SyncStateStore.current.locked

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
        shownNeedsAuth = needsAuth()
        shownChoosing = choosing()
        shownBusy = busy()
        val connecting = SyncStateStore.current == SyncState.Connecting
        val r = Ref<HTMLElement>()
        val statusRef = Ref<HTMLElement>()
        val dotRef = Ref<HTMLElement>()
        addToBody {
            syncModalChrome(
                r, onBackdropClick = { (onDismiss ?: { hide() })() },
                minWidth = 380.px, maxWidth = 460.px, pad = 24.px,
            ) {
                div {
                    css { fontSize = 20.px; fontWeight = FontWeight.w600; marginBottom = 18.px
                        color = ThemeManager.current.menuTitle }
                    +"Cloud Sync"
                }

                // Provider chooser: only before connecting (provider is fixed for the life of a sync).
                if (!shownConnected) {
                    sectionLabel("Provider")
                    providerChooser(connecting)
                    if (selectedProvider == SyncProvider.GOOGLE_DRIVE) {
                        helperText("Google signs you out periodically, so Drive needs an occasional " +
                            "Reconnect. Dropbox or a local folder + Syncthing stay connected without that.")
                    }
                    field(folderHint(), ROOT_ID, pendingFolder ?: cfg.rootFolder)
                } else if (editingFolder) {
                    field(folderHint(), ROOT_ID, pendingFolder ?: cfg.rootFolder)
                }

                // Status banner — the most important thing in the dialog, so it leads with a
                // state-coloured dot and reads at full size (was buried in 12px grey text). When
                // connected the account + folder ride along as a calm subtitle.
                statusBanner(statusRef, dotRef, cfg)

                // Google's GIS token model has no refresh token, so its session lapses periodically.
                // That's expected — frame it as a calm reconnect, not an error.
                if (SyncStateStore.current == SyncState.NeedsReconnect &&
                    cfg.provider == SyncProvider.GOOGLE_DRIVE) {
                    helperText("Google signs you out periodically — that's expected, not an error. " +
                        "Just click Reconnect to resume.")
                }

                div {
                    css {
                        display = Display.flex; flexDirection = FlexDirection.row; flexWrap = FlexWrap.wrap
                        marginTop = 18.px; paddingTop = 18.px
                        borderTop = Border(1.px, BorderStyle.solid, SyncColors.border)
                    }
                    when {
                        // The folder AND this device both have content — the user picks the direction
                        // (we only get here after Connect has checked the remote and the local device).
                        SyncStateStore.current == SyncState.ChoosingDirection -> {
                            button("Sync from folder", primary = true,
                                hint = "Merge your boards with what's already in the folder") {
                                controller.chooseInitialSync(SyncController.InitialSyncMode.FROM)
                            }
                            button("Sync to folder",
                                hint = "Replace the folder's contents with this device's boards") {
                                controller.chooseInitialSync(SyncController.InitialSyncMode.TO)
                            }
                        }

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
                            val needsAuth = needsAuth()
                            val synced = SyncStateStore.current == SyncState.Synced
                            if (needsAuth) {
                                // reconnect(), NOT connect(): a fresh connect wipes the sync
                                // bookkeeping, and with it the version vectors of edits made
                                // while auth was lapsed — the first pull would revert them.
                                button("Reconnect", primary = true) { controller.reconnect() }
                            }
                            // Clear dismiss once the work is done — the primary CTA right after a sync.
                            button("Done", primary = synced && !needsAuth) { (onDismiss ?: { hide() })() }
                            button("Sync now", primary = !needsAuth && !synced) { controller.syncNow() }
                            button("Change folder") { editingFolder = true; render() }
                            button("Disconnect") { controller.disconnect() }
                        }
                    }
                }

                // Board restore lives in the canvas context menu (Backup & Restore), not here:
                // the rolling backup is not a property of cloud sync.

                onMount {
                    rootEl = r.element
                    val s = statusRef.element
                    val dot = dotRef.element
                    with(eventBus) {
                        s.on<SyncStatusChanged> {
                            s.textContent = stateText(it.state)
                            dot.style.backgroundColor = dotColor(it.state).value   // keep the signal live
                        }
                        s.on<SyncConflict> { s.textContent = "Conflict on ${it.path}: ${it.winner} kept" }
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

    /** A small uppercase section heading above a group of controls. */
    private fun FlowContent.sectionLabel(text: String) {
        div {
            css {
                fontSize = 11.px; fontWeight = FontWeight.w600; textTransform = TextTransform.uppercase
                letterSpacing = 0.5.px; opacity = 0.6; marginBottom = 6.px
            }
            +text
        }
    }

    /** Muted explanatory copy (Drive reconnect notes, etc.) with comfortable line-height. */
    private fun FlowContent.helperText(text: String) {
        div {
            css {
                fontSize = 12.px; lineHeight = LineHeight("1.45"); opacity = 0.8
                marginTop = 8.px; marginBottom = 2.px
            }
            +text
        }
    }

    /** The prominent status row: a state-coloured dot + the live status text, with the account and
     *  folder as a calm subtitle when connected. [statusRef]/[dotRef] are updated live on status change. */
    private fun FlowContent.statusBanner(statusRef: Ref<HTMLElement>, dotRef: Ref<HTMLElement>, cfg: SyncConfigDoc) {
        val state = SyncStateStore.current
        div {
            css {
                display = Display.flex; alignItems = Align.center
                padding = Padding(vertical = 12.px, horizontal = 14.px)
                backgroundColor = SyncColors.panelBg
                border = Border(1.px, BorderStyle.solid, SyncColors.border)
                borderRadius = 6.px
                marginTop = 4.px; marginBottom = 4.px
            }
            div {
                ref(dotRef)
                css {
                    minWidth = 10.px; width = 10.px; height = 10.px; borderRadius = 50.pct
                    backgroundColor = dotColor(state); marginRight = 12.px
                }
            }
            div {
                css { display = Display.flex; flexDirection = FlexDirection.column; minWidth = 0.px }
                div { ref(statusRef); css { fontSize = 14.px; fontWeight = FontWeight.w600 }; +stateText(state) }
                bannerSubtitle(cfg)?.let { sub ->
                    div {
                        css {
                            fontSize = 12.px; opacity = 0.72; marginTop = 3.px
                            overflow = Overflow.hidden; textOverflow = TextOverflow.ellipsis; whiteSpace = WhiteSpace.nowrap
                        }
                        +sub
                    }
                }
            }
        }
    }

    /** The account + folder line shown under the status when connected; null otherwise. */
    private fun bannerSubtitle(cfg: SyncConfigDoc): String? =
        if (!cfg.cloudConnected) null
        else "${cfg.provider.displayName} · ${cfg.accountEmail ?: "signed in"} · ${folderLabel(cfg.rootFolder)}"

    private fun dotColor(state: SyncState): Color = when (state) {
        SyncState.Synced -> SyncColors.statusOk
        SyncState.Connecting, SyncState.Reconciling, SyncState.Syncing -> SyncColors.statusBusy
        SyncState.Offline, SyncState.NeedsReconnect, SyncState.ChoosingDirection -> SyncColors.statusAttention
        is SyncState.Error -> SyncColors.statusError
        SyncState.NotConfigured -> SyncColors.statusIdle
    }

    private fun folderHint(): String = when (selectedProvider) {
        SyncProvider.DROPBOX -> "Sync folder (required — e.g. thedozone2)"
        SyncProvider.GOOGLE_DRIVE -> "Sync folder (blank = \"thedozone2\")"
        SyncProvider.LOCAL_FOLDER -> ""   // never shown: local is excluded from this modal's chooser
    }

    private fun folderLabel(raw: String): String =
        SyncPaths.rootPrefix(raw).ifEmpty { "(provider root)" }

    private fun stateText(state: SyncState): String = when (state) {
        SyncState.NotConfigured -> "Not connected"
        SyncState.Connecting -> "Connecting…"
        SyncState.ChoosingDirection -> "This folder already has content — choose a direction"
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
            css { marginBottom = 12.px; display = Display.flex; flexDirection = FlexDirection.column }
            div { css { fontSize = 12.px; marginBottom = 6.px; opacity = 0.85 }; +labelText }
            input(type = InputType.text) {
                id = inputId
                attributes["value"] = value
                css {
                    width = 100.pct; boxSizing = BoxSizing.borderBox
                    padding = Padding(vertical = 9.px, horizontal = 10.px)
                    backgroundColor = SyncColors.inputBg
                    color = ThemeManager.current.menuFg
                    border = Border(1.px, BorderStyle.solid, SyncColors.inputBorder)
                    borderRadius = 4.px
                }
            }
        }
    }

    private fun FlowContent.button(
        text: String, primary: Boolean = false, enabled: Boolean = true, hint: String? = null, onPress: () -> Unit,
    ) =
        // While sync is LOCKED (Connecting / Reconciling) no button is clickable — the work in flight
        // must finish first. (render() re-runs on the locked-state flip so this reflects live.)
        modalButton(text, primary, enabled && !SyncStateStore.current.locked, hint, onPress)

    companion object {
        private const val ROOT_ID = "tdz-sync-root"
    }
}

/** Cloud Sync modal only deals with cloud providers; map an active LOCAL_FOLDER to a sane default. */
private fun SyncProvider.coerceCloud(): SyncProvider =
    if (this == SyncProvider.LOCAL_FOLDER) SyncProvider.DROPBOX else this
