package tdz.sync

import kotlinx.browser.window
import tdz.controller.events.ColumnsChanged
import tdz.controller.events.Event
import tdz.model.CanvasName
import tdz.model.ColumnId
import zoned.framework.events.EventBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The rolling restore point: one slot, refreshed on the timer beat ONLY when data changed. */
class PeriodicBackupTest {

    @Test
    fun autoSlotRoundTripsScopedToTheGivenKeys() {
        window.localStorage.setItem("NEWCANVAStest-pb", "v1")
        window.localStorage.setItem("SYNC_STATEstate", "vv-current")

        assertTrue(LocalBackup.autoSnapshot(), "snapshot written")
        assertNotNull(LocalBackup.autoTimestamp(), "timestamp recorded")
        assertEquals("v1", LocalBackup.autoValue("NEWCANVAStest-pb"), "single values readable")

        // Data and bookkeeping both move on; a new key appears after the snapshot.
        window.localStorage.setItem("NEWCANVAStest-pb", "v2-wiped")
        window.localStorage.setItem("SYNC_STATEstate", "vv-newer")
        window.localStorage.setItem("COLUMNtest-pb_9", "post-backup column")

        assertTrue(LocalBackup.restoreAutoKeys(setOf("NEWCANVAStest-pb", "COLUMNtest-pb_9")))

        assertEquals("v1", window.localStorage.getItem("NEWCANVAStest-pb"), "listed key rolled back")
        assertEquals(null, window.localStorage.getItem("COLUMNtest-pb_9"),
            "a listed key absent from the backup is REMOVED (true rollback)")
        assertEquals("vv-newer", window.localStorage.getItem("SYNC_STATEstate"),
            "unlisted keys are untouched — the live sync vectors must survive a restore")
        window.localStorage.removeItem("NEWCANVAStest-pb")
    }

    @Test
    fun tickWritesOnlyWhenDirty() {
        window.localStorage.setItem("NEWCANVAStest-tick", "data")   // something to back up
        val bus = EventBus<Event>()
        PeriodicBackup.init(bus)

        bus.fire(ColumnsChanged(CanvasName("_pb-tick"), setOf(ColumnId("1"))))
        assertTrue(PeriodicBackup.tick(), "a change since the last beat refreshes the slot")
        assertFalse(PeriodicBackup.tick(), "an idle beat must NOT rewrite the slot")

        bus.fire(ColumnsChanged(CanvasName("_pb-tick"), setOf(ColumnId("1"))))
        assertTrue(PeriodicBackup.tick(), "the next change re-arms the beat")
        window.localStorage.removeItem("NEWCANVAStest-tick")
    }
}
