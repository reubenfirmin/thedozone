package tdz.sync

import kotlinx.browser.window
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalBackupTest {

    @BeforeTest fun clear() { window.localStorage.clear() }
    @AfterTest  fun clean() { window.localStorage.clear() }

    @Test
    fun autoSnapshotExcludesSecretsAndBackupKeys() {
        window.localStorage.setItem("col-0:_", "data")
        window.localStorage.setItem("tdz.sync.driveToken", "secret")
        window.localStorage.setItem("tdz_dropbox_code_verifier", "pkce")  // the CONFIRMED verifier key

        assertTrue(LocalBackup.autoSnapshot())
        val raw = window.localStorage.getItem("tdz.backup.auto")!!
        assertTrue(!raw.contains("driveToken"), "drive token excluded from backup")
        assertTrue(!raw.contains("pkce"), "dropbox verifier excluded from backup")
        assertTrue(!raw.contains("tdz.backup."), "backup slot not nested in backup")
    }

    @Test
    fun restoreAutoKeysReplaysSubsetAndRemovesNewerKeys() {
        window.localStorage.setItem("col-0:_", "original")
        assertTrue(LocalBackup.autoSnapshot())
        // A bad edit changes an existing key and adds one absent from the backup.
        window.localStorage.setItem("col-0:_", "clobbered")
        window.localStorage.setItem("col-9:_", "added later")

        assertTrue(LocalBackup.restoreAutoKeys(setOf("col-0:_", "col-9:_")))
        assertEquals("original", window.localStorage.getItem("col-0:_"), "restored value")
        assertEquals(null, window.localStorage.getItem("col-9:_"), "key absent in backup removed")
    }

    @Test
    fun autoValueReadsOneKeyOrNull() {
        window.localStorage.setItem("newcanvas:_", "canvas-data")
        assertTrue(LocalBackup.autoSnapshot())
        assertEquals("canvas-data", LocalBackup.autoValue("newcanvas:_"))
        assertEquals(null, LocalBackup.autoValue("missing"))
    }

    @Test
    fun autoTimestampNullUntilSnapshot() {
        assertEquals(null, LocalBackup.autoTimestamp())
        window.localStorage.setItem("col-0:_", "data")
        assertTrue(LocalBackup.autoSnapshot())
        assertTrue(LocalBackup.autoTimestamp() != null, "timestamp set after snapshot")
    }
}
