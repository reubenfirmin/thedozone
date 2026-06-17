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

    @Test
    fun unreachableRemoteIsOfflineNotError() {
        // A fetch blocked/never-reaching the server (offline, extension/proxy block, DNS, dropped
        // connection) throws a browser-specific TypeError. It's a calm, auto-recovering condition —
        // not a hard Error. Cover the three major browsers' wordings.
        assertEquals(SyncState.Offline, syncFailureState(Exception("Dropbox upload failed: Failed to fetch")))
        assertEquals(SyncState.Offline, syncFailureState(Exception("NetworkError when attempting to fetch resource.")))
        assertEquals(SyncState.Offline, syncFailureState(Exception("Load failed")))
    }
}
