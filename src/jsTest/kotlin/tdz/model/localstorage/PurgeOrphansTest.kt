package tdz.model.localstorage

import kotlinx.browser.window
import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.Mode
import tdz.model.Model
import tdz.model.Split
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * purgeOrphans removes OUR orphaned keys only: a column-mode flag whose column no longer exists in
 * any manifest, and the legacy DROPBOX_CONFIG key. It keeps valid keys, leaves orphaned column
 * *content* alone (reported, not deleted — it may be real data), and NEVER touches keys outside our
 * namespaces (another app shares this origin).
 */
class PurgeOrphansTest {

    @BeforeTest fun clear() { window.localStorage.clear() }
    @AfterTest fun clean() { window.localStorage.clear() }

    @Test
    fun purgesOrphanModesAndLegacyKeepsValidAndForeignReportsContent() {
        val name = CanvasName("_po")
        val model = Model(name)
        model.addCanvas(Canvas(name, null, listOf(ColumnId("0"), ColumnId("1")),
            listOf(ColumnLayoutSpec(ColumnId("0"), Split.HORIZONTAL, listOf(ColumnId("1"))))))
        model.updateColumn(name, ColumnId("1"), "# real")          // valid content
        model.setColumnMode(name, ColumnId("1"), Mode.CARD)        // valid mode
        model.updateColumn(name, ColumnId("99"), "# orphan")       // orphaned content (col 99 not in manifest)
        model.setColumnMode(name, ColumnId("99"), Mode.CARD)       // orphaned mode

        window.localStorage.setItem("DROPBOX_CONFIGconfig", "{}")   // legacy — ours, purge
        window.localStorage.setItem("employeeTableColumns", "{}")   // foreign — must survive

        val (purged, orphanContent) = model.purgeOrphans()

        assertNull(window.localStorage.getItem(CanvasStorageKeys.columnMode(name, ColumnId("99"))), "orphan mode purged")
        assertNotNull(window.localStorage.getItem(CanvasStorageKeys.columnMode(name, ColumnId("1"))), "valid mode kept")
        assertNull(window.localStorage.getItem("DROPBOX_CONFIGconfig"), "legacy key purged")
        assertNotNull(window.localStorage.getItem("employeeTableColumns"), "foreign key untouched")
        assertNotNull(window.localStorage.getItem(CanvasStorageKeys.column(name, ColumnId("99"))),
            "orphaned content is NOT deleted (may be real data)")
        assertTrue(orphanContent.any { it.contains("99") }, "orphaned content is reported instead")
        assertTrue(purged >= 2, "purged the orphan mode + legacy key (got $purged)")
    }

    @Test
    fun deletingAColumnAlsoClearsItsModeFlag() {
        val name = CanvasName("_po2")
        val model = Model(name)
        model.addCanvas(Canvas(name, null, listOf(ColumnId("0"), ColumnId("1")),
            listOf(ColumnLayoutSpec(ColumnId("0"), Split.HORIZONTAL, listOf(ColumnId("1"))))))
        model.updateColumn(name, ColumnId("1"), "# x")
        model.setColumnMode(name, ColumnId("1"), Mode.CARD)
        assertNotNull(window.localStorage.getItem(CanvasStorageKeys.columnMode(name, ColumnId("1"))))

        model.deleteColumn(name, ColumnId("1"))

        assertNull(window.localStorage.getItem(CanvasStorageKeys.columnMode(name, ColumnId("1"))),
            "deleting a column must not leave its mode flag orphaned")
    }
}
