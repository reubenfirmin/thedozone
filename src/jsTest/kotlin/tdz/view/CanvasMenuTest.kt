package tdz.view

import tdz.canvasPages
import tdz.helpPages
import tdz.model.Split
import tdz.settingsPages
import tdz.view.menu.MenuItem
import tdz.view.menu.MenuSection
import tdz.view.theme.Theme
import zoned.framework.routing.Router
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanvasMenuTest {

    // The menu's doc links are typed route references (helpPages.x.path()), which fail loudly for
    // unmounted collections by design — so mount them exactly as main() does. Idempotent.
    @BeforeTest
    fun mountRoutes() = Router.mount(canvasPages, helpPages, settingsPages)

    private class RecordingActions : CanvasMenuActions {
        var clearedDone = false
        var toggled = 0
        var columnProps = 0
        var downloaded = 0
        val splits = mutableListOf<Split>()
        val distributes = mutableListOf<Split>()
        var deleted = 0
        val themed = mutableListOf<Theme>()
        var cloudSync = 0
        var localFolderSync = 0
        var downloadedPng = 0
        var uploadedNew = 0
        var uploadedHere = 0
        val openedDocs = mutableListOf<String>()
        override fun toggleColumn() { toggled++ }
        override fun columnProperties() { columnProps++ }
        override fun download() { downloaded++ }
        override fun splitEven(split: Split) { splits += split }
        override fun distribute(split: Split) { distributes += split }
        override fun deleteColumn() { deleted++ }
        override fun setTheme(theme: Theme) { themed += theme }
        override fun openCloudSync() { cloudSync++ }
        override fun openLocalFolderSync() { localFolderSync++ }
        override fun downloadPng() { downloadedPng++ }
        override fun uploadNewCanvas() { uploadedNew++ }
        override fun uploadCardsHere() { uploadedHere++ }
        override fun clearDone() { clearedDone = true }
        override fun openDoc(path: String) { openedDocs += path }
    }

    // Recurse into submenus so items moved into flyouts are still found.
    private fun flatten(items: List<MenuItem>): List<MenuItem> =
        items.flatMap { listOf(it) + flatten(it.submenu ?: emptyList()) }

    private fun allItems(sections: List<MenuSection>) = flatten(sections.flatMap { it.items })

    @Test
    fun cardModeShowsSwitchToEditorEnabled() {
        val toggle = canvasSections(Mode.CARD, RecordingActions()).first().items.first()
        assertEquals("Switch to Editor", toggle.label)
        assertTrue(toggle.enabled)
    }

    @Test
    fun editorModeShowsSwitchToCard() {
        val toggle = canvasSections(Mode.EDITOR, RecordingActions()).first().items.first()
        assertEquals("Switch to Card", toggle.label)
        assertTrue(toggle.enabled)
    }

    @Test
    fun noColumnUnderCursorDisablesToggle() {
        val toggle = canvasSections(null, RecordingActions()).first().items.first()
        assertFalse(toggle.enabled)
    }

    @Test
    fun toggleInvokesAction() {
        val actions = RecordingActions()
        canvasSections(Mode.CARD, actions).first().items.first().onSelect()
        assertEquals(1, actions.toggled)
    }

    @Test
    fun downloadIsWired() {
        val actions = RecordingActions()
        val download = allItems(canvasSections(Mode.CARD, actions)).first { it.label == "Download" }
        assertTrue(download.enabled)
        download.onSelect()
        assertEquals(1, actions.downloaded)
    }

    @Test
    fun splitSectionHasFourIconItemsWiredToActions() {
        val actions = RecordingActions()
        val split = canvasSections(Mode.CARD, actions).first { it.title == "Split" }
        assertEquals(4, split.items.size)
        // All split entries are icons (no text label rendering)
        split.items.forEach { assertNotNull(it.icon, "split item '${it.label}' should have an icon") }
        // Even = SplitColumn, Distribute = AddColumn; vertical and horizontal each
        split.items[0].onSelect(); split.items[1].onSelect()  // even V, even H
        split.items[2].onSelect(); split.items[3].onSelect()  // distribute V, distribute H
        assertEquals(listOf(Split.VERTICAL, Split.HORIZONTAL), actions.splits)
        assertEquals(listOf(Split.VERTICAL, Split.HORIZONTAL), actions.distributes)
    }

    @Test
    fun splitItemsDisabledWhenNoColumn() {
        val split = canvasSections(null, RecordingActions()).first { it.title == "Split" }
        split.items.forEach { assertFalse(it.enabled, "split item should be disabled with no column") }
    }

    @Test
    fun deleteColumnEnabledWithColumnAndWired() {
        val actions = RecordingActions()
        val item = allItems(canvasSections(Mode.CARD, actions)).first { it.label == "Delete Column" }
        assertTrue(item.enabled)
        item.onSelect()
        assertEquals(1, actions.deleted)
    }

    @Test
    fun deleteColumnDisabledWithNoColumn() {
        val item = allItems(canvasSections(null, RecordingActions())).first { it.label == "Delete Column" }
        assertFalse(item.enabled)
    }

    @Test
    fun clearDoneIsWiredAndEnabled() {
        val actions = RecordingActions()
        val items = allItems(canvasSections(Mode.CARD, actions))
        val item = items.first { it.label == "Clear Done" }
        assertTrue(item.enabled)
        item.onSelect()
        assertTrue(actions.clearedDone)
    }

    @Test
    fun documentationItemsAreEnabledAndOpenTheRightDoc() {
        val actions = RecordingActions()
        val items = allItems(canvasSections(Mode.CARD, actions))

        val releaseNotes = items.first { it.label == "Release Notes" }
        val shortcuts = items.first { it.label == "Keyboard Shortcuts" }
        val formatting = items.first { it.label == "Card Formatting" }

        assertTrue(releaseNotes.enabled, "Release Notes should be enabled")
        assertTrue(shortcuts.enabled, "Keyboard Shortcuts should be enabled")
        assertTrue(formatting.enabled, "Card Formatting should be enabled")

        releaseNotes.onSelect()
        shortcuts.onSelect()
        formatting.onSelect()

        assertEquals(
            listOf(
                "/help/release-notes",
                "/help/shortcuts",
                "/help/formatting",
            ),
            actions.openedDocs,
        )
    }

    @Test
    fun downloadPngIsWiredAndEnabled() {
        val actions = RecordingActions()
        val item = allItems(canvasSections(Mode.CARD, actions)).first { it.label == "Download PNG" }
        assertTrue(item.enabled)
        item.onSelect()
        assertEquals(1, actions.downloadedPng)
    }

    @Test
    fun uploadAsNewCanvasIsWiredAndEnabled() {
        val actions = RecordingActions()
        val item = allItems(canvasSections(Mode.CARD, actions)).first { it.label == "Upload as New Canvas" }
        assertTrue(item.enabled)
        item.onSelect()
        assertEquals(1, actions.uploadedNew)
    }

    @Test
    fun uploadCardsHereIsWiredAndEnabled() {
        val actions = RecordingActions()
        val item = allItems(canvasSections(Mode.CARD, actions)).first { it.label == "Upload Cards Here" }
        assertTrue(item.enabled)
        item.onSelect()
        assertEquals(1, actions.uploadedHere)
    }

    @Test
    fun backupsAndHelpAreSubmenus() {
        val top = canvasSections(Mode.CARD, RecordingActions()).flatMap { it.items }
        val backups = assertNotNull(top.firstOrNull { it.label == "Backups & Export" }?.submenu)
        assertTrue(backups.any { it.label == "Download" && it.enabled }, "Download stays wired inside the submenu")
        val docs = assertNotNull(top.firstOrNull { it.label == "Help" }?.submenu)
        assertTrue(docs.any { it.label == "Release Notes" }, "Help submenu holds its entries")
    }

    @Test
    fun syncSectionHasCloudAndLocalItemsWired() {
        val actions = RecordingActions()
        val items = allItems(canvasSections(Mode.CARD, actions))
        val cloud = items.first { it.label == "Cloud Sync…" }
        assertTrue(cloud.enabled)
        cloud.onSelect()
        assertEquals(1, actions.cloudSync)
        val local = items.first { it.label == "Local Folder Sync…" }
        assertTrue(local.enabled)
        local.onSelect()
        assertEquals(1, actions.localFolderSync)
    }

    @Test
    fun localFolderItemDisabledWhenUnsupported() {
        val items = allItems(canvasSections(Mode.CARD, RecordingActions(), localFolderSupported = false))
        val local = items.first { it.label == "Local Folder Sync (Chrome only)" }
        assertFalse(local.enabled)
    }

    @Test
    fun themeSubmenuListsSelectableThemes() {
        val actions = RecordingActions()
        val theme = allItems(canvasSections(Mode.CARD, actions)).first { it.label == "Theme" }
        val sub = assertNotNull(theme.submenu, "Theme should open a submenu")
        assertTrue(sub.any { it.label == "Blue Room" }, "submenu should list Blue Room")
        assertTrue(sub.any { it.label == "DOS" }, "submenu should list DOS")
        assertTrue(sub.all { it.enabled }, "theme entries are selectable")
        sub.first { it.label == "DOS" }.onSelect()
        assertEquals("DOS", actions.themed.single().name)
    }
}
