package tdz.view

import tdz.controller.Downloader
import tdz.controller.PngExporter
import tdz.controller.Uploader
import tdz.controller.io.ImportMode
import tdz.controller.events.AddColumn
import tdz.controller.events.ClearDone
import tdz.controller.events.DeleteColumn
import tdz.helpPages
import tdz.legalPages
import tdz.settingsPages
import zoned.framework.routing.Router
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.RefreshCanvas
import tdz.controller.events.SplitColumn
import kotlinx.browser.window
import tdz.model.Model
import tdz.model.Split
import tdz.sync.LocalBackup
import tdz.sync.SyncBootstrap
import tdz.view.menu.MenuItem
import tdz.view.menu.MenuSection
import tdz.view.menu.splitIcon
import tdz.view.theme.Theme
import tdz.view.theme.ThemeManager
import tdz.view.theme.themes
import web.mouse.MouseEvent

/** Dynamic actions the canvas menu invokes; kept as an interface so sections are unit-testable. */
interface CanvasMenuActions {
    fun toggleColumn()
    fun columnProperties()        // open the column properties modal (wallpaper / color)
    fun download()                // export the canvas as a .tdz file
    fun splitEven(split: Split)   // SplitColumn: divide focused column into 2 equal panes
    fun distribute(split: Split)  // AddColumn: add a pane to the containing layout
    fun deleteColumn()            // DeleteColumn: remove the column + rearrange the layout
    fun revertColumn()            // discard the open editor's unsaved buffer, back to card mode
    fun setTheme(theme: Theme)    // switch the active UI theme
    fun openCloudSync()           // open the cloud-sync setup modal
    fun openLocalFolderSync()     // open the local-folder sync modal
    fun downloadPng()             // rasterize the canvas to a .png image
    fun uploadNewCanvas()         // pick a .tdz file and import it as a new canvas
    fun uploadCardsHere()         // pick a .tdz file and merge its cards into the current canvas
    fun restoreBoardBackup()      // roll THIS board back to the rolling 30-minute backup
    fun clearDone()
    fun openDoc(path: String)     // navigate to an in-app help route (e.g. /help/shortcuts)
    fun reportBug()               // open the issue tracker in a new browser tab
    fun installApp()              // fire the captured PWA install prompt
}

/** "14:05" — the rolling backup's age, shown right on the menu item. */
private fun backupTimeLabel(ts: Double): String = kotlin.js.Date(ts).let { d ->
    "${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}"
}

/**
 * Pure builder for the canvas context-menu sections.
 * @param columnMode mode of the column under the cursor, or null if none (toggle disabled).
 */
fun canvasSections(
    columnMode: Mode?,
    actions: CanvasMenuActions,
    localFolderSupported: Boolean = true,
    /** When the rolling 30-minute backup was taken (epoch ms), or null when none exists yet. */
    backupTimestamp: Double? = null,
    /** Whether the browser has offered a PWA install prompt we can fire (adds an "Install app" item). */
    installAvailable: Boolean = false,
): List<MenuSection> = listOf(
    MenuSection(
        items = buildList {
            add(MenuItem(
                label = columnMode?.let { if (it == Mode.CARD) "Edit" else "Save" } ?: "Switch column",
                enabled = columnMode != null,
                onSelect = actions::toggleColumn,
            ))
            if (columnMode == Mode.EDITOR) {
                // Editing this column: offer Save's counterpart, and drop the card-mode actions
                // (Clear Done / Delete Column) — they don't apply while the markdown is open.
                add(MenuItem(label = "Undo edits", onSelect = actions::revertColumn))
            } else {
                add(MenuItem(label = "Clear Done", shortcut = "Ctrl+;", onSelect = actions::clearDone))
                add(MenuItem(label = "Delete Column", enabled = columnMode != null, onSelect = actions::deleteColumn))
            }
        },
    ),
    MenuSection(
        title = "Split",
        items = listOf(
            MenuItem("Split even, side by side", enabled = columnMode != null,
                icon = splitIcon(2, Split.VERTICAL), onSelect = { actions.splitEven(Split.VERTICAL) }),
            MenuItem("Split even, stacked", enabled = columnMode != null,
                icon = splitIcon(2, Split.HORIZONTAL), onSelect = { actions.splitEven(Split.HORIZONTAL) }),
            MenuItem("Distribute, side by side", enabled = columnMode != null,
                icon = splitIcon(3, Split.VERTICAL), onSelect = { actions.distribute(Split.VERTICAL) }),
            MenuItem("Distribute, stacked", enabled = columnMode != null,
                icon = splitIcon(3, Split.HORIZONTAL), onSelect = { actions.distribute(Split.HORIZONTAL) }),
        ),
    ),
    MenuSection(
        items = listOf(
            MenuItem(
                label = "Backup & Restore",
                submenu = listOf(
                    MenuItem(label = "Download PNG", onSelect = actions::downloadPng),
                    MenuItem(label = "Download", onSelect = actions::download),
                    MenuItem(label = "Upload as New Canvas", onSelect = actions::uploadNewCanvas),
                    MenuItem(label = "Upload Cards Here", onSelect = actions::uploadCardsHere),
                    MenuItem(
                        label = backupTimestamp?.let { "Restore this board (${backupTimeLabel(it)})" }
                            ?: "Restore this board",
                        enabled = backupTimestamp != null,
                        onSelect = actions::restoreBoardBackup,
                    ),
                ),
            ),
        ),
    ),
    MenuSection(
        title = "Sync",
        items = listOf(
            MenuItem(label = "Cloud Sync…", onSelect = actions::openCloudSync),
            MenuItem(
                label = if (localFolderSupported) "Local Folder Sync…" else "Local Folder Sync (Chrome only)",
                enabled = localFolderSupported,
                onSelect = actions::openLocalFolderSync,
            ),
        ),
    ),
    MenuSection(
        items = listOf(
            MenuItem(
                label = "Theme",
                submenu = themes.map { theme ->
                    MenuItem(
                        label = theme.name,
                        active = theme == ThemeManager.current,
                        onSelect = { actions.setTheme(theme) },
                    )
                },
            ),
            MenuItem(label = "Column properties", enabled = columnMode != null, onSelect = actions::columnProperties),
        ),
    ),
    MenuSection(
        items = listOf(
            MenuItem(
                label = "Help",
                submenu = listOf(
                    MenuItem(label = "Release Notes",
                        onSelect = { actions.openDoc(helpPages.releaseNotes.path()) }),
                    MenuItem(label = "Keyboard Shortcuts", shortcut = "F1",
                        onSelect = { actions.openDoc(helpPages.shortcuts.path()) }),
                    MenuItem(label = "Card Formatting", shortcut = "F2",
                        onSelect = { actions.openDoc(helpPages.formatting.path()) }),
                    MenuItem(label = "Privacy", onSelect = { actions.openDoc(legalPages.privacy.path()) }),
                    MenuItem(label = "Report a Bug", onSelect = actions::reportBug),
                ),
            ),
        ),
    ),
) + listOfNotNull(
    // Only present when the browser reported the app is installable (and not already installed).
    if (installAvailable) MenuSection(
        items = listOf(MenuItem(label = "Install app", onSelect = actions::installApp)),
    ) else null,
)

/**
 * Build the canvas menu's section-resolver for right-click. Resolves the column under the
 * cursor (if any) for the context-sensitive toggle, and wires Download.
 */
fun canvasMenu(
    columns: CanvasColumns,
    model: Model,
    eventBus: EventBus<Event>,
    downloader: Downloader,
    uploader: Uploader,
): (MouseEvent) -> List<MenuSection> = { event ->
    // DOM hit-test (not the geometric boundaries) so right-clicking anywhere over a column targets it,
    // including empty columns made short by a content-heavy sibling in the same stacked split.
    val columnId = columns.columnAt(event.clientX, event.clientY)
    val mode = columnId?.let { columns.modeOf(it) }
    val actions = object : CanvasMenuActions {
        override fun toggleColumn() { columnId?.let { columns.toggleMode(it) } }
        override fun columnProperties() {
            columnId?.let { ColumnPropertiesModal(model, eventBus).show(model.currentCanvas().name, it) }
        }
        override fun download() { downloader.download(model.currentCanvas()) }
        override fun splitEven(split: Split) {
            columnId?.let { eventBus.fire(SplitColumn(model.currentCanvas().name, split, it)) }
        }
        override fun distribute(split: Split) {
            columnId?.let { eventBus.fire(AddColumn(model.currentCanvas().name, split, it)) }
        }
        override fun deleteColumn() {
            columnId?.let { eventBus.fire(DeleteColumn(model.currentCanvas().name, it)) }
        }
        override fun revertColumn() { columnId?.let { columns.revertMode(it) } }
        override fun setTheme(theme: Theme) {
            ThemeManager.use(theme)
            model.setTheme(theme.name)   // remember across reloads (localStorage via the model)
            // structural=false: a theme switch is purely local UI — it must not push to sync.
            eventBus.fire(RefreshCanvas(model.currentCanvas().name, structural = false))
        }
        override fun clearDone() { eventBus.fire(ClearDone(model.currentCanvas().name)) }
        override fun openCloudSync() { Router.navigate(settingsPages.sync.path()) }
        override fun openLocalFolderSync() { Router.navigate(settingsPages.folder.path()) }
        override fun downloadPng() {
            val name = model.currentCanvas().name
            PngExporter().export("app-container", "${name.normalized()}.png") {
                // structural=false: just restoring images stripped for the capture — no sync push.
                eventBus.fire(RefreshCanvas(name, structural = false))
            }
        }
        override fun uploadNewCanvas() { uploader.upload(ImportMode.NEW_CANVAS) }
        override fun uploadCardsHere() { uploader.upload(ImportMode.HERE) }
        override fun restoreBoardBackup() {
            val ts = LocalBackup.autoTimestamp() ?: return
            if (window.confirm("Restore this board from the ${backupTimeLabel(ts)} backup? " +
                    "Changes to it made since then will be lost.")) {
                if (SyncBootstrap.controller.restoreAutoBackup()) window.alert("Board restored.")
                else window.alert("No backup of this board available yet.")
            }
        }
        override fun openDoc(path: String) { zoned.framework.routing.Router.navigate(path) }
        override fun reportBug() { window.open("https://github.com/reubenfirmin/thedozone2", "_blank", "noopener") }
        override fun installApp() { PwaInstall.prompt() }
    }
    canvasSections(mode, actions, tdz.sync.local.HandleStore.supported(), LocalBackup.autoTimestamp(),
        PwaInstall.isAvailable())
}
