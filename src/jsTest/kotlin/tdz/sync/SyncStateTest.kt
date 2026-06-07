package tdz.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncStateTest {

    @Test
    fun connectingAndReconcilingAreLocked() {
        assertTrue(SyncState.Connecting.locked)
        assertTrue(SyncState.Reconciling.locked)
    }

    @Test
    fun otherStatesAreUnlocked() {
        assertFalse(SyncState.NotConfigured.locked)
        assertFalse(SyncState.Synced.locked)
        assertFalse(SyncState.Syncing.locked)
        assertFalse(SyncState.Offline.locked)
        assertFalse(SyncState.Error("boom").locked)
    }
}
