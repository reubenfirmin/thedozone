package tdz.view

import tdz.controller.Downloader
import tdz.controller.PngExporter
import tdz.controller.Uploader
import tdz.controller.io.ImportMode
import tdz.controller.events.AddColumn
import tdz.controller.events.DeleteColumn
import tdz.controller.events.EventBus
import tdz.controller.events.RefreshCanvas
import tdz.controller.events.SplitColumn
import tdz.model.Model
import tdz.model.Split
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
    fun columnProperties()        // open the column properties modal (wallpaper / colour)
    fun download()                // export the canvas as a .tdz file
    fun splitEven(split: Split)   // SplitColumn: divide focused column into 2 equal panes
    fun distribute(split: Split)  // AddColumn: add a pane to the containing layout
    fun deleteColumn()            // DeleteColumn: remove the column + rearrange the layout
    fun setTheme(theme: Theme)    // switch the active UI theme
    fun openCloudSync()           // open the cloud-sync setup modal
    fun openLocalFolderSync()     // open the local-folder sync modal
    fun downloadPng()             // rasterize the canvas to a .png image
    fun uploadNewCanvas()         // pick a .tdz file and import it as a new canvas
    fun uploadCardsHere()         // pick a .tdz file and merge its cards into the current canvas
    fun openDoc(path: String)     // open a help doc (static /docs/*.html) in a new browser tab
}

private val STUB: () -> Unit = {}

/**
 * Pure builder for the canvas context-menu sections.
 * @param columnMode mode of the column under the cursor, or null if none (toggle disabled).
 */
fun canvasSections(
    columnMode: Mode?,
    actions: CanvasMenuActions,
    localFolderSupported: Boolean = true,
): List<MenuSection> = listOf(
    MenuSection(
        items = listOf(
            MenuItem(
                label = columnMode?.let { "Switch to ${it.invert().displayName()}" } ?: "Switch column",
                enabled = columnMode != null,
                onSelect = actions::toggleColumn,
            ),
            MenuItem(label = "Clear Done", enabled = false, onSelect = STUB),
            MenuItem(label = "Delete Column", enabled = columnMode != null, onSelect = actions::deleteColumn),
        ),
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
                label = "Backups & Export",
                submenu = listOf(
                    MenuItem(label = "Download PNG", onSelect = actions::downloadPng),
                    MenuItem(label = "Download", onSelect = actions::download),
                    MenuItem(label = "Upload as New Canvas", onSelect = actions::uploadNewCanvas),
                    MenuItem(label = "Upload Cards Here", onSelect = actions::uploadCardsHere),
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
                label = "Documentation",
                submenu = listOf(
                    MenuItem(label = "Release Notes",
                        onSelect = { actions.openDoc("/docs/release-notes.html") }),
                    MenuItem(label = "Keyboard Shortcuts", shortcut = "F1",
                        onSelect = { actions.openDoc("/docs/keyboard-shortcuts.html") }),
                    MenuItem(label = "Card Formatting", shortcut = "F2",
                        onSelect = { actions.openDoc("/docs/card-formatting.html") }),
                ),
            ),
        ),
    ),
)

/**
 * Build the canvas menu's section-resolver for right-click. Resolves the column under the
 * cursor (if any) for the context-sensitive toggle, and wires Download.
 */
fun canvasMenu(
    columns: CanvasColumns,
    model: Model,
    eventBus: EventBus,
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
        override fun setTheme(theme: Theme) {
            ThemeManager.use(theme)
            model.setTheme(theme.name)   // remember across reloads (localStorage via the model)
            // structural=false: a theme switch is purely local UI — it must not push to sync.
            eventBus.fire(RefreshCanvas(model.currentCanvas().name, structural = false))
        }
        override fun openCloudSync() { tdz.sync.SyncBootstrap.modal.show() }
        override fun openLocalFolderSync() { tdz.sync.SyncBootstrap.localFolderModal.show() }
        override fun downloadPng() {
            val name = model.currentCanvas().name
            PngExporter().export("app-container", "${name.normalized()}.png") {
                // structural=false: just restoring images stripped for the capture — no sync push.
                eventBus.fire(RefreshCanvas(name, structural = false))
            }
        }
        override fun uploadNewCanvas() { uploader.upload(ImportMode.NEW_CANVAS) }
        override fun uploadCardsHere() { uploader.upload(ImportMode.HERE) }
        override fun openDoc(path: String) { kotlinx.browser.window.open(path, "_blank") }
    }
    canvasSections(mode, actions, tdz.sync.local.HandleStore.supported())
}
