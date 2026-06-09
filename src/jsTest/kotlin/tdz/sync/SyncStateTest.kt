package tdz.sync

import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertFalse(SyncState.NeedsReconnect.locked)
        assertFalse(SyncState.Error("boom").locked)
    }

    @Test
    fun googleSessionTimeoutIsNeedsReconnectNotError() {
        // A failed silent Google token mint is expected, not an error — see syncFailureState.
        val state = syncFailureState(NeedsInteractiveAuthException("Google session expired"))
        assertEquals(SyncState.NeedsReconnect, state)
    }

    @Test
    fun genuineFailureIsErrorWithMessage() {
        val state = syncFailureState(Exception("Dropbox token revoked"))
        assertEquals(SyncState.Error("Dropbox token revoked"), state)
    }

    @Test
    fun failureWithoutMessageFallsBackToGenericError() {
        val state = syncFailureState(Exception())
        assertEquals(SyncState.Error("sync error"), state)
    }
}
