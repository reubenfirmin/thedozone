package tdz.sync

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.coroutines.test.runTest
import tdz.controller.events.ColumnsChanged
import tdz.controller.events.Event
import tdz.controller.events.RefreshCanvas
import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.LayoutTemplate
import tdz.model.LayoutTemplates
import tdz.model.Model
import tdz.model.Split
import tdz.model.localstorage.CanvasDoc
import tdz.model.localstorage.SyncConfigDoc
import tdz.model.localstorage.SyncEntry
import tdz.model.localstorage.SyncStateDoc
import zoned.framework.events.EventBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Layout IS content": the manifest/layout is resolved by the SAME causal rules as a column, via the
 * structure version vector (svv). Newer layout wins; concurrent / no-history merges keeping both
 * sides' columns PLACED (never the old "remote shape wins" that clobbered a local board and orphaned
 * its columns).
 */
class LayoutSyncTest {

    private val root = "/n"
    private val home = CanvasName("")
    private fun manifestPath() = SyncPaths.manifestPath(root, home)
    private fun doc(cols: List<Int>, contains: List<Int>): String =
        CanvasDoc(home, null, cols.map { ColumnId(it.toString()) },
            listOf(ColumnLayoutSpec(ColumnId(cols.first().toString()), Split.VERTICAL, contains.map { ColumnId(it.toString()) }))).encode()
    private fun remoteManifest(cols: List<Int>, contains: List<Int>, svv: VersionVector): String =
        ManifestVv.write(doc(cols, contains), root, home, emptyMap(), svv)

    private class FakeStorage(initial: List<Pair<RemoteFile, String>>) : RemoteStorage {
        val files = initial.associate { it.first.path to it }.toMutableMap()
        private var rev = 0
        override suspend fun list(folder: String) = RemoteListing(files.values.map { it.first }, rootExists = true)
        override suspend fun download(path: String) = files.getValue(path).let { it.second to it.first }
        override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile {
            rev++; val f = RemoteFile(path, "r$rev", rev.toDouble()); files[path] = f to content; return f
        }
        override suspend fun delete(path: String) { files.remove(path) }
        override suspend fun revoke() {}
        override suspend fun columnVv(path: String): VersionVector = emptyMap()
    }

    private fun engine(storage: RemoteStorage, local: MutableMap<String, String>) = SyncEngine(
        storage = storage, readLocal = { local[it] }, writeLocal = { p, c -> local[p] = c }, deleteLocal = { local.remove(it) })

    /** 1. Fresh connect (no history) with edits on both sides → MERGE: every column placed, none lost. */
    @Test
    fun firstConnectMergesLayoutsKeepingBothSidesPlaced() = runTest {
        val mp = manifestPath()
        val storage = FakeStorage(listOf(RemoteFile(mp, "rR", 100.0) to remoteManifest(listOf(0, 3, 6), listOf(3, 6), mapOf("me" to 4L))))
        val local = mutableMapOf(mp to doc(listOf(0, 1, 2), listOf(1, 2)))   // local has cols 1,2
        engine(storage, local).pull(root, SyncStateDoc.EMPTY)               // no history -> CONCURRENT
        val merged = CanvasDoc.decode(local.getValue(mp))
        assertTrue(merged.columns.containsAll(listOf(1, 2, 3, 6).map { ColumnId(it.toString()) }), "all columns kept: ${merged.columns}")
        val placed = merged.layouts.flatMap { it.contains }.toSet()
        assertTrue(placed.containsAll(listOf(1, 2, 3, 6).map { ColumnId(it.toString()) }),
            "every content column is PLACED in the layout (none orphaned): $placed")
    }

    /** 1b. Concurrent DELETE (other device) vs an unrelated structural edit (this device): the delete
     *  is HONORED via the manifest tombstone — the column is not resurrected by the additive merge,
     *  and its local content is dropped. */
    @Test
    fun concurrentDeleteIsHonoredNotResurrected() = runTest {
        val mp = manifestPath()
        val col3 = SyncPaths.columnPath(root, home, ColumnId("3"))
        // Remote (device A) deleted column 3; its manifest carries the tombstone. svv {A:2} (structural).
        val remoteMan = ManifestVv.write(doc(listOf(0), listOf()), root, home, emptyMap(),
            svv = mapOf("A" to 2L), deleted = mapOf(ColumnId("3") to mapOf("shared" to 1L)))
        val storage = FakeStorage(listOf(RemoteFile(mp, "rR", 100.0) to remoteMan))
        // Local (device B) still has column 3 (unedited); synced before; svv {B:1} -> concurrent with A.
        val local = mutableMapOf(mp to doc(listOf(0, 3), listOf(3)), col3 to "# Three\n* keep?\n")
        val state = SyncStateDoc.EMPTY
            .with(mp, SyncEntry("rOld", 50.0, base = doc(listOf(0, 3), listOf(3)), vv = mapOf("B" to 1L)))
            .with(col3, SyncEntry("rc3", 50.0, base = "x", vv = mapOf("shared" to 1L)))
        val result = engine(storage, local).pull(root, state)
        assertTrue(ColumnId("3") !in CanvasDoc.decode(local.getValue(mp)).columns, "deleted column not resurrected")
        assertTrue(col3 !in local, "the deleted column's local content is dropped")
        assertTrue(result.state.entries[col3]?.tombstone == true, "tombstone learned + kept so it keeps propagating")
    }

    /** 1c. Concurrent DELETE vs an EDIT of that same column: the edit WINS (keep-edit-else-delete) —
     *  the column survives, its tombstone is cleared, and it's re-pushed so the remote re-learns it. */
    @Test
    fun concurrentDeleteVsEditKeepsTheEditedColumn() = runTest {
        val mp = manifestPath()
        val col3 = SyncPaths.columnPath(root, home, ColumnId("3"))
        val remoteMan = ManifestVv.write(doc(listOf(0), listOf()), root, home, emptyMap(),
            svv = mapOf("A" to 2L), deleted = mapOf(ColumnId("3") to mapOf("shared" to 1L)))
        val storage = FakeStorage(listOf(RemoteFile(mp, "rR", 100.0) to remoteMan))
        // Local (B) edited column 3 beyond the clock the deleter saw: vv {shared:1, B:1}.
        val local = mutableMapOf(mp to doc(listOf(0, 3), listOf(3)), col3 to "# Three\n* my edit\n")
        val state = SyncStateDoc.EMPTY
            .with(mp, SyncEntry("rOld", 50.0, base = doc(listOf(0, 3), listOf(3)), vv = mapOf("B" to 1L)))
            .with(col3, SyncEntry("rc3", 50.0, base = "x", vv = mapOf("shared" to 1L, "B" to 1L)))
        val result = engine(storage, local).pull(root, state)
        assertTrue(ColumnId("3") in CanvasDoc.decode(local.getValue(mp)).columns, "the edited column survives the delete")
        assertTrue(col3 in local && local.getValue(col3).contains("my edit"), "the concurrent edit is preserved")
        assertTrue(result.state.entries[col3]?.tombstone != true, "tombstone cleared — the edit won")
        assertTrue(result.keptLocalPaths.contains(col3), "the survivor is re-pushed so the remote re-learns it")
    }

    /** 1d. Adopting a causally-NEWER remote also LEARNS its tombstones, so the deletion keeps
     *  propagating from this device and a stale re-import can't resurrect the column later. */
    @Test
    fun adoptingANewerRemoteLearnsItsTombstones() = runTest {
        val mp = manifestPath()
        val col3 = SyncPaths.columnPath(root, home, ColumnId("3"))
        // Remote (A) is newer (svv {A:2}) and has deleted column 3; its manifest carries the tombstone.
        val remoteMan = ManifestVv.write(doc(listOf(0), listOf()), root, home, emptyMap(),
            svv = mapOf("A" to 2L), deleted = mapOf(ColumnId("3") to mapOf("shared" to 1L)))
        val storage = FakeStorage(listOf(RemoteFile(mp, "rR", 100.0) to remoteMan))
        // Local is behind (svv {A:1}) and still has column 3.
        val local = mutableMapOf(mp to doc(listOf(0, 3), listOf(3)), col3 to "# Three\n")
        val state = SyncStateDoc.EMPTY
            .with(mp, SyncEntry("rOld", 50.0, base = doc(listOf(0, 3), listOf(3)), vv = mapOf("A" to 1L)))
            .with(col3, SyncEntry("rc3", 50.0, base = "x", vv = mapOf("shared" to 1L)))
        val result = engine(storage, local).pull(root, state)
        assertTrue(ColumnId("3") !in CanvasDoc.decode(local.getValue(mp)).columns, "adopted the newer remote (no col 3)")
        assertTrue(result.state.entries[col3]?.tombstone == true,
            "the adopted deletion is learned as a tombstone so it keeps propagating")
    }

    /** 1e. A BRAND-NEW device: its board is the untouched default placeholder (SINGLE_PANE, i.e. root
     *  split HORIZONTAL over one EMPTY column). That is not a layout the user built, so there is
     *  nothing to protect: the remote board must be adopted WHOLE. Merging it instead imposed the
     *  placeholder's split direction and left its empty column as an extra pane (the reported bug:
     *  "one extra column, and they're horizontal, not vertical"). */
    @Test
    fun brandNewDeviceAdoptsRemoteLayoutInsteadOfMergingItsEmptyPlaceholder() = runTest {
        val mp = manifestPath()
        val placeholder = ColumnId("ph")
        val remoteCols = listOf(ColumnId("a"), ColumnId("b"), ColumnId("c"))
        val remoteDoc = CanvasDoc(home, null, listOf(ColumnId.ROOT) + remoteCols,
            listOf(ColumnLayoutSpec(ColumnId.ROOT, Split.VERTICAL, remoteCols))).encode()
        val storage = FakeStorage(listOf(RemoteFile(mp, "rR", 100.0) to
            ManifestVv.write(remoteDoc, root, home, emptyMap(), svv = mapOf("other" to 3L))))
        // The fresh install's board: root HORIZONTAL over ONE column that has no local content at all.
        val local = mutableMapOf(mp to CanvasDoc(home, null, listOf(ColumnId.ROOT, placeholder),
            listOf(ColumnLayoutSpec(ColumnId.ROOT, Split.HORIZONTAL, listOf(placeholder)))).encode())

        engine(storage, local).pull(root, SyncStateDoc.EMPTY)   // no history -> CONCURRENT

        val merged = CanvasDoc.decode(local.getValue(mp))
        val layoutIds = merged.layouts.map { it.columnId }.toSet()
        assertEquals(remoteCols, merged.columns.filterNot { it in layoutIds },
            "the empty placeholder column is NOT carried in as an extra pane")
        assertEquals(Split.VERTICAL, merged.layouts.first { it.columnId == merged.columns.first() }.split,
            "the remote board's split direction survives (not the placeholder's HORIZONTAL)")
    }

    /** 2. Remote layout causally newer (the other device changed it) → adopt it. */
    @Test
    fun remoteLayoutNewerWins() = runTest {
        val mp = manifestPath()
        val storage = FakeStorage(listOf(RemoteFile(mp, "rR", 100.0) to remoteManifest(listOf(0, 3, 6), listOf(3, 6), mapOf("me" to 2L))))
        val local = mutableMapOf(mp to doc(listOf(0, 1, 2), listOf(1, 2)))
        val state = SyncStateDoc.EMPTY.with(mp, SyncEntry("rOld", 50.0, base = doc(listOf(0, 1, 2), listOf(1, 2)), vv = mapOf("me" to 1L)))
        engine(storage, local).pull(root, state)
        assertEquals(listOf(0, 3, 6).map { ColumnId(it.toString()) }, CanvasDoc.decode(local.getValue(mp)).columns, "adopted the newer remote layout")
    }

    /** 3. Local layout causally newer → keep mine and push it up. */
    @Test
    fun localLayoutNewerWinsAndPushes() = runTest {
        val mp = manifestPath()
        val storage = FakeStorage(listOf(RemoteFile(mp, "rR", 100.0) to remoteManifest(listOf(0, 3, 6), listOf(3, 6), mapOf("me" to 1L))))
        val local = mutableMapOf(mp to doc(listOf(0, 1, 2), listOf(1, 2)))
        val state = SyncStateDoc.EMPTY.with(mp, SyncEntry("rOld", 999.0, base = doc(listOf(0, 7), listOf(7)), vv = mapOf("me" to 5L)))
        val result = engine(storage, local).pull(root, state)
        assertEquals(listOf(0, 1, 2).map { ColumnId(it.toString()) }, CanvasDoc.decode(local.getValue(mp)).columns, "kept the local layout")
        assertTrue(result.keptLocalPaths.contains(mp), "local layout pushes up so the remote converges")
    }

    /** 3b. END-TO-END, the reported bug: a BRAND-NEW computer (untouched default board, never synced)
     *  connects FROM a folder holding a real board. The board must come back EXACTLY as it was, not
     *  the placeholder's shape with the remote's columns bolted on. */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun brandNewDeviceRestoresTheRemoteBoardExactly(): dynamic = GlobalScope.promise {
        val name = "_new-device"
        val canvas = CanvasName(name)
        val model = Model(canvas)
        val bus = EventBus<Event>()
        val r = "/$name"
        model.writeSyncConfig(SyncConfigDoc(SyncProvider.DROPBOX, enabled = false, rootFolder = r,
            refreshToken = null, accountEmail = null))
        model.writeSyncState(SyncStateDoc.EMPTY)
        // The fresh install's untouched board: SINGLE_PANE, its one column still blank.
        model.addCanvas(LayoutTemplates.canvasFromTemplate(canvas, null, LayoutTemplate.SINGLE_PANE))
        // The remote holds the user's real board: three columns, side by side (VERTICAL).
        val cols = listOf(ColumnId("a"), ColumnId("b"), ColumnId("c"))
        val remoteDoc = CanvasDoc(canvas, null, listOf(ColumnId.ROOT) + cols,
            listOf(ColumnLayoutSpec(ColumnId.ROOT, Split.VERTICAL, cols))).encode()
        val storage = FakeStorage(listOf(
            RemoteFile(SyncPaths.manifestPath(r, canvas), "rM", 100.0) to
                ManifestVv.write(remoteDoc, r, canvas, emptyMap(), svv = mapOf("other" to 2L)),
            RemoteFile(SyncPaths.columnPath(r, canvas, cols[0]), "rA", 100.0) to "# A\n* a\n",
            RemoteFile(SyncPaths.columnPath(r, canvas, cols[1]), "rB", 100.0) to "# B\n* b\n",
            RemoteFile(SyncPaths.columnPath(r, canvas, cols[2]), "rC", 100.0) to "# C\n* c\n",
        ))
        val c = SyncController(model, bus, storageOverride = storage)
        c.connectOverride = { ConnectResult(accountEmail = null, refreshToken = "test-token") }
        try {
            c.connect(SyncController.InitialSyncMode.FROM)
            delay(250)

            val restored = model.getCanvas(canvas) ?: error("the board vanished")
            assertEquals(cols, restored.leafColumns(),
                "exactly the remote's columns, no extra empty pane from the default board")
            assertEquals(Split.VERTICAL, restored.root().split,
                "the board's split direction is restored (not the default board's HORIZONTAL)")
            assertTrue((model.getCanvasColumnRaw(canvas, cols[1]) ?: "").contains("* b"), "cards restored")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }

    /** 4. Clear a column while CONNECTED → the structural edit bumps the svv and propagates to remote. */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun clearingAColumnWhileConnectedPropagates(): dynamic = GlobalScope.promise {
        val name = "_layout-clear"
        val canvas = CanvasName(name)
        val model = Model(canvas)
        val bus = EventBus<Event>()
        val storage = FakeStorage(emptyList())
        val r = "/$name"
        model.writeSyncConfig(SyncConfigDoc(SyncProvider.DROPBOX, enabled = true, rootFolder = r, refreshToken = "t", accountEmail = "x"))
        model.writeSyncState(SyncStateDoc.EMPTY)
        model.addCanvas(Canvas(canvas, null, listOf(ColumnId("0"), ColumnId("1"), ColumnId("2")),
            listOf(ColumnLayoutSpec(ColumnId("0"), Split.VERTICAL, listOf(ColumnId("1"), ColumnId("2"))))))
        model.updateColumn(canvas, ColumnId("1"), "# One\n* a\n")
        model.updateColumn(canvas, ColumnId("2"), "# Two\n* b\n")
        val c = SyncController(model, bus, storageOverride = storage).also { it.pushDebounceMs = 5 }
        c.start(); delay(40)
        val mPath = SyncPaths.manifestPath(r, canvas)
        try {
            // Establish the baseline on the remote: cols 0,1,2.
            bus.fire(ColumnsChanged(canvas, setOf(ColumnId("1"), ColumnId("2"))))
            bus.fire(RefreshCanvas(canvas, structural = true))
            delay(80)
            assertTrue(ColumnId("2") in CanvasDoc.decode(storage.files.getValue(mPath).second).columns,
                "setup: remote starts with column 2")

            // Clear column 2 (a structural edit) while connected → must propagate.
            model.deleteColumn(canvas, ColumnId("2"))
            model.addCanvas(Canvas(canvas, null, listOf(ColumnId("0"), ColumnId("1")),
                listOf(ColumnLayoutSpec(ColumnId("0"), Split.VERTICAL, listOf(ColumnId("1"))))))
            model.changeCanvas(canvas)
            bus.fire(RefreshCanvas(canvas, structural = true))
            delay(80)

            assertTrue(ColumnId("2") !in CanvasDoc.decode(storage.files.getValue(mPath).second).columns,
                "the removed column propagated to the remote manifest: ${CanvasDoc.decode(storage.files.getValue(mPath).second).columns}")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }
}
