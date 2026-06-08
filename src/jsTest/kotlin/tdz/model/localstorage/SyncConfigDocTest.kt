package tdz.model.localstorage

import tdz.sync.SyncProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncConfigDocTest {

    @Test
    fun localFolderNameSurvivesJsonRoundTrip() {
        val cfg = SyncConfigDoc(
            provider = SyncProvider.LOCAL_FOLDER, enabled = true, rootFolder = "",
            refreshToken = null, accountEmail = null, localFolderName = "Projects",
        )
        val back = SyncConfigDoc.fromJson(cfg.toJson())
        assertEquals(SyncProvider.LOCAL_FOLDER, back.provider)
        assertEquals("Projects", back.localFolderName)
    }

    @Test
    fun legacyJsonWithoutLocalFolderNameDecodesToNull() {
        val legacy = SyncConfigDoc(
            provider = SyncProvider.DROPBOX, enabled = true, rootFolder = "",
            refreshToken = "tok", accountEmail = null,
        )
        // Round-trip drops nothing, but a missing key must decode to null, not crash.
        assertEquals(null, SyncConfigDoc.fromJson(legacy.toJson()).localFolderName)
    }

    @Test
    fun localConnectedRequiresLocalProviderAndName() {
        val local = SyncConfigDoc(SyncProvider.LOCAL_FOLDER, true, "", null, null, "Projects")
        assertTrue(local.localConnected)
        assertFalse(local.cloudConnected)
        assertTrue(local.connected)
    }

    @Test
    fun cloudConnectedRequiresCloudProviderCredentials() {
        val dbx = SyncConfigDoc(SyncProvider.DROPBOX, true, "", "tok", null, null)
        assertTrue(dbx.cloudConnected)
        assertFalse(dbx.localConnected)
        // A local provider with no name is neither connected.
        val emptyLocal = SyncConfigDoc(SyncProvider.LOCAL_FOLDER, true, "", null, null, null)
        assertFalse(emptyLocal.localConnected)
        assertFalse(emptyLocal.connected)
    }
}
