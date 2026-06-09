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
    fun snapshotThenRestoreRoundTrips() {
        window.localStorage.setItem("newcanvas:_", "canvas-data")
        window.localStorage.setItem("col-0:_", "card markdown")

        val id = LocalBackup.snapshot()
        assertTrue(id != null, "snapshot returns an id")

        // Simulate a destructive sync wiping the data.
        window.localStorage.removeItem("newcanvas:_")
        window.localStorage.removeItem("col-0:_")

        LocalBackup.restore(id!!)

        assertEquals("canvas-data", window.localStorage.getItem("newcanvas:_"))
        assertEquals("card markdown", window.localStorage.getItem("col-0:_"))
    }

    @Test
    fun retainsOnlyMostRecentBackups() {
        window.localStorage.setItem("col-0:_", "v")
        val ids = (1..(LocalBackup.MAX_BACKUPS + 2)).map { LocalBackup.snapshot()!! }
        val kept = LocalBackup.list()
        assertEquals(LocalBackup.MAX_BACKUPS, kept.size, "old backups pruned")
        assertTrue(kept.containsAll(ids.takeLast(LocalBackup.MAX_BACKUPS)), "newest kept")
    }

    @Test
    fun snapshotDoesNotCopyBackupOrTokenKeys() {
        window.localStorage.setItem("col-0:_", "data")
        window.localStorage.setItem("tdz.sync.driveToken", "secret")
        val id = LocalBackup.snapshot()!!
        val raw = window.localStorage.getItem("tdz.backup.$id")!!
        assertTrue(!raw.contains("driveToken"), "token excluded from backups")
        assertTrue(!raw.contains("tdz.backup."), "backups not nested in backups")
    }

    @Test
    fun restoreReturnsFalseForMissingBackup() {
        assertEquals(false, LocalBackup.restore("does-not-exist"))
    }

    @Test
    fun restoreReturnsTrueOnSuccess() {
        window.localStorage.setItem("col-0:_", "data")
        val id = LocalBackup.snapshot()!!
        window.localStorage.removeItem("col-0:_")
        assertEquals(true, LocalBackup.restore(id))
        assertEquals("data", window.localStorage.getItem("col-0:_"))
    }

    @Test
    fun snapshotExcludesDropboxVerifier() {
        window.localStorage.setItem("col-0:_", "data")
        window.localStorage.setItem("tdz_dropbox_code_verifier", "pkce")  // use the CONFIRMED verifier key
        val id = LocalBackup.snapshot()!!
        val raw = window.localStorage.getItem("tdz.backup.$id")!!
        assertTrue(!raw.contains("pkce"), "dropbox verifier excluded from backup")
    }

    @Test
    fun restoreRemovesKeysAddedAfterSnapshot() {
        window.localStorage.setItem("col-0:_", "original")
        val id = LocalBackup.snapshot()!!
        // A bad sync adds a new key and changes an existing one.
        window.localStorage.setItem("col-9:_", "pulled-in junk")
        window.localStorage.setItem("col-0:_", "clobbered")
        // A secret present at restore time must be preserved (it was excluded from the backup).
        window.localStorage.setItem("tdz.sync.driveToken", "live-token")

        assertEquals(true, LocalBackup.restore(id))

        assertEquals("original", window.localStorage.getItem("col-0:_"), "restored value")
        assertEquals(null, window.localStorage.getItem("col-9:_"), "key added after snapshot removed")
        assertEquals("live-token", window.localStorage.getItem("tdz.sync.driveToken"), "secret preserved")
    }
}
