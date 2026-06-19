package tdz.sync

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.coroutines.test.runTest
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.localstorage.CanvasDoc
import tdz.model.localstorage.SyncEntry
import tdz.model.localstorage.SyncStateDoc
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first-connect data-loss contract: when this device has NO sync history for a file (no rev,
 * no merge base — fresh connect, or content created while sync was off), the version-vector
 * comparison proves nothing about the LOCAL CONTENT. In particular the remote may carry THIS
 * device's clock from a previous sync life (DeviceId survives disconnect), making a stale remote
 * look "causally ahead" of a brand-new local board. With no history, neither side may silently
 * win: columns merge keeping BOTH sides, manifests union so no local column is orphaned.
 */
class FirstConnectProtectionTest {

    private class FakeStorage(
        initial: List<Pair<RemoteFile, String>>,
        remoteVv: Map<String, VersionVector> = emptyMap(),
    ) : RemoteStorage {
        val files = initial.associate { it.first.path to it }.toMutableMap()
        private var revCounter = 0
        private val remoteVv: MutableMap<String, VersionVector> = remoteVv.toMutableMap()
        override suspend fun list(folder: String) = RemoteListing(files.values.map { it.first }, rootExists = true)
        override suspend fun download(path: String) = files.getValue(path).let { it.second to it.first }
        override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile {
            revCounter++
            val f = RemoteFile(path, "rev$revCounter", revCounter.toDouble())
            files[path] = f to content
            return f
        }
        override suspend fun delete(path: String) { files.remove(path) }
        override suspend fun revoke() {}
        override suspend fun columnVv(path: String): VersionVector = remoteVv[path] ?: emptyMap()
    }

    private fun engine(storage: RemoteStorage, local: MutableMap<String, String>) = SyncEngine(
        storage = storage,
        readLocal = { local[it] },
        writeLocal = { p, c -> local[p] = c },
        deleteLocal = { local.remove(it) },
    )

    /** The reported bug: seeded {me:1} vs a stale remote carrying this device's OLD clock {me:5}. */
    @Test
    fun staleRemoteWithOurOldClockMustNotOverwriteAFreshLocalBoard() = runTest {
        val path = "/n/_/col-1.md"
        val storage = FakeStorage(
            listOf(RemoteFile(path, "rRemote", 100.0) to "# Old remote board\n* stale\n"),
            remoteVv = mapOf(path to mapOf("me" to 5L)),
        )
        val local = mutableMapOf(path to "# My new board\n* fresh work\n")
        // Exactly what seedLocalVvs fabricates at connect: no rev, no base, {self:1}.
        val state = SyncStateDoc.EMPTY.with(path,
            SyncEntry(rev = null, localUpdatedAt = 200.0, base = null, vv = mapOf("me" to 1L)))

        val result = engine(storage, local).pull(root = "/n", state = state)

        val content = local.getValue(path)
        assertTrue(content.contains("fresh work"), "local board MUST survive: <<$content>>")
        assertTrue(content.contains("stale"), "remote content is kept too (union, no silent loss)")
        assertTrue(result.keptLocalPaths.contains(path), "merged content pushes back up")
    }

    /** Same hole without seeding: untracked local content vs any remote vv. */
    @Test
    fun untrackedLocalContentMustNotBeOverwrittenByADominantRemote() = runTest {
        val path = "/n/_/col-1.md"
        val storage = FakeStorage(
            listOf(RemoteFile(path, "rRemote", 100.0) to "remote\n"),
            remoteVv = mapOf(path to mapOf("other" to 3L)),
        )
        val local = mutableMapOf(path to "local\n")

        engine(storage, local).pull(root = "/n", state = SyncStateDoc.EMPTY)

        val content = local.getValue(path)
        assertTrue(content.contains("local"), "untracked local content survives: <<$content>>")
        assertTrue(content.contains("remote"))
    }

    /** Manifest LWW with no history defaulted local to 0.0 — remote always won, orphaning columns. */
    @Test
    fun remoteManifestMustNotOrphanLocalOnlyColumnsAtFirstConnect() = runTest {
        val canvas = CanvasName("_fc-manifest")
        val manifestPath = "/n/${canvas.normalized()}/${SyncPaths.MANIFEST}"
        val localManifest = CanvasDoc(canvas, null, listOf(ColumnId("1"), ColumnId("2")), emptyList()).encode()
        val remoteManifest = CanvasDoc(canvas, null, listOf(ColumnId("1")), emptyList()).encode()
        val storage = FakeStorage(listOf(RemoteFile(manifestPath, "rM", 100.0) to remoteManifest))
        val local = mutableMapOf(manifestPath to localManifest)

        val result = engine(storage, local).pull(root = "/n", state = SyncStateDoc.EMPTY)

        val merged = CanvasDoc.decode(local.getValue(manifestPath))
        assertTrue(ColumnId("2") in merged.columns,
            "the local-only column stays in the manifest — never orphaned: ${merged.columns}")
        assertTrue(ColumnId("1") in merged.columns, "shared column kept")
        assertTrue(result.keptLocalPaths.contains(manifestPath), "union manifest pushes back up")
    }

    /** The reported scenario end-to-end: a board built with sync OFF, then a connect to a Drive
     *  folder holding a stale prior sync (carrying this device's old clock). Nothing may be lost. */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun connectingToAStaleRemoteFolderPreservesTheLocalBoard(): dynamic = GlobalScope.promise {
        val name = "_fc-e2e"
        val canvas = CanvasName(name)
        val model = tdz.model.Model(canvas)
        val bus = zoned.framework.events.EventBus<tdz.controller.events.Event>()
        val root = "/$name"
        val self = DeviceId.get()
        model.writeSyncConfig(tdz.model.localstorage.SyncConfigDoc(
            provider = SyncProvider.DROPBOX, enabled = false, rootFolder = root,
            refreshToken = null, accountEmail = null,
        ))
        model.writeSyncState(SyncStateDoc.EMPTY)
        val template = tdz.model.LayoutTemplates.canvasFromTemplate(canvas, null, tdz.model.LayoutTemplate.SINGLE_PANE)
        model.addCanvas(template)
        val col = template.firstNonLayout()
        model.updateColumn(canvas, col, "# My board\n* fresh work\n")

        val colPath = SyncPaths.columnPath(root, canvas, col)
        val manifestPath = SyncPaths.manifestPath(root, canvas)
        val storage = FakeStorage(
            listOf(
                RemoteFile(manifestPath, "rM", 100.0) to
                    CanvasDoc(canvas, null, template.columns, template.layouts).encode(),
                RemoteFile(colPath, "rC", 100.0) to "# Old remote\n* stale\n",
            ),
            remoteVv = mapOf(colPath to mapOf(self to 5L)),   // OUR clock from a previous sync life
        )
        val c = SyncController(model, bus, storageOverride = storage)
        c.connectOverride = { ConnectResult(accountEmail = null, refreshToken = "test-token") }
        try {
            c.connect(SyncController.InitialSyncMode.FROM)
            delay(200)

            val content = model.getCanvasColumnRaw(canvas, col) ?: ""
            assertTrue(content.contains("fresh work"),
                "the board built before connecting MUST survive the first sync: <<$content>>")
            assertTrue(content.contains("stale"), "the remote's content is kept too")
            val remoteAfter = storage.files.getValue(colPath).second
            assertTrue(remoteAfter.contains("fresh work") && remoteAfter.contains("stale"),
                "the remote converges on the union: <<$remoteAfter>>")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }

    /** The PRODUCTION scenario: the home board is the ROOT canvas (empty name). Connecting to a
     *  Dropbox folder that already holds a stale prior sync must NOT overwrite the local home board. */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun connectingTheRootHomeBoardToAStaleRemoteFolderPreservesIt(): dynamic = GlobalScope.promise {
        val root = ""                       // default Dropbox root — the production home-board case
        val rootCanvas = CanvasName("")
        val model = tdz.model.Model(rootCanvas)
        val bus = zoned.framework.events.EventBus<tdz.controller.events.Event>()
        val self = DeviceId.get()
        model.writeSyncConfig(tdz.model.localstorage.SyncConfigDoc(
            provider = SyncProvider.DROPBOX, enabled = false, rootFolder = root,
            refreshToken = null, accountEmail = null,
        ))
        model.writeSyncState(SyncStateDoc.EMPTY)
        val template = tdz.model.LayoutTemplates.canvasFromTemplate(rootCanvas, null, tdz.model.LayoutTemplate.SINGLE_PANE)
        model.addCanvas(template)
        model.updateColumn(rootCanvas, ColumnId("1"), "# My home\n* fresh work\n")

        val colPath = SyncPaths.columnPath(root, rootCanvas, ColumnId("1"))      // "/col-1.md"
        val manifestPath = SyncPaths.manifestPath(root, rootCanvas)            // "/_canvas.json"
        val storage = FakeStorage(
            listOf(
                RemoteFile(manifestPath, "rM", 100.0) to
                    CanvasDoc(rootCanvas, null, template.columns, template.layouts).encode(),
                RemoteFile(colPath, "rC", 100.0) to "# Old remote\n* stale\n",
            ),
            remoteVv = mapOf(colPath to mapOf(self to 5L)),   // our clock from a previous sync life
        )
        val c = SyncController(model, bus, storageOverride = storage)
        c.connectOverride = { ConnectResult(accountEmail = null, refreshToken = "test-token") }
        try {
            c.connect(SyncController.InitialSyncMode.FROM)
            delay(200)

            val content = model.getCanvasColumnRaw(rootCanvas, ColumnId("1")) ?: ""
            assertTrue(content.contains("fresh work"),
                "the ROOT home board built before connecting MUST survive the first sync: <<$content>>")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }

    /** Connecting a MULTI-column home board to a remote folder that holds a smaller/older board:
     *  every local column must survive — the stale shared column merges, local-only columns stay. */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun connectingMultiColumnHomeToASmallerStaleRemotePreservesEveryColumn(): dynamic = GlobalScope.promise {
        val root = ""
        val rootCanvas = CanvasName("")
        val model = tdz.model.Model(rootCanvas)
        val bus = zoned.framework.events.EventBus<tdz.controller.events.Event>()
        val self = DeviceId.get()
        model.writeSyncConfig(tdz.model.localstorage.SyncConfigDoc(
            provider = SyncProvider.DROPBOX, enabled = false, rootFolder = root,
            refreshToken = null, accountEmail = null,
        ))
        model.writeSyncState(SyncStateDoc.EMPTY)
        // Local home board: three content columns 1,2,3 (a real multi-pane board).
        val cols = listOf(ColumnId("0"), ColumnId("1"), ColumnId("2"), ColumnId("3"))
        val layouts = listOf(tdz.model.ColumnLayoutSpec(ColumnId("0"), tdz.model.Split.VERTICAL, listOf(ColumnId("1"), ColumnId("2"), ColumnId("3"))))
        model.addCanvas(tdz.model.Canvas(rootCanvas, null, cols, layouts))
        model.updateColumn(rootCanvas, ColumnId("1"), "# One\n* local one\n")
        model.updateColumn(rootCanvas, ColumnId("2"), "# Two\n* local two\n")
        model.updateColumn(rootCanvas, ColumnId("3"), "# Three\n* local three\n")

        // Remote folder already holds a SMALLER, older board: manifest lists only [0,1], col-1 stale,
        // carrying THIS device's old clock.
        val col1Path = SyncPaths.columnPath(root, rootCanvas, ColumnId("1"))
        val manifestPath = SyncPaths.manifestPath(root, rootCanvas)
        val storage = FakeStorage(
            listOf(
                RemoteFile(manifestPath, "rM", 100.0) to
                    CanvasDoc(rootCanvas, null, listOf(ColumnId("0"), ColumnId("1")),
                        listOf(tdz.model.ColumnLayoutSpec(ColumnId("0"), tdz.model.Split.VERTICAL, listOf(ColumnId("1"))))).encode(),
                RemoteFile(col1Path, "rC", 100.0) to "# One\n* stale remote one\n",
            ),
            remoteVv = mapOf(col1Path to mapOf(self to 5L)),
        )
        val c = SyncController(model, bus, storageOverride = storage)
        c.connectOverride = { ConnectResult(accountEmail = null, refreshToken = "test-token") }
        try {
            c.connect(SyncController.InitialSyncMode.FROM)
            delay(250)

            assertTrue((model.getCanvasColumnRaw(rootCanvas, ColumnId("1")) ?: "").contains("local one"),
                "shared column 1 keeps local content")
            assertTrue((model.getCanvasColumnRaw(rootCanvas, ColumnId("2")) ?: "").contains("local two"),
                "local-only column 2 MUST survive connect")
            assertTrue((model.getCanvasColumnRaw(rootCanvas, ColumnId("3")) ?: "").contains("local three"),
                "local-only column 3 MUST survive connect")
            assertTrue(ColumnId("2") in (model.getCanvas(rootCanvas)?.columns ?: emptyList()) &&
                ColumnId("3") in (model.getCanvas(rootCanvas)?.columns ?: emptyList()),
                "manifest keeps local-only columns (not orphaned by the smaller remote)")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }

    /** No-ancestor merge keeps BOTH sides — never the lexicographic tiebreak (which discards one). */
    @Test
    fun nullBaseMergeUnionsBothSidesWithoutConflict() {
        val result = ColumnMerge.mergeColumn(
            local = "# Mine\n* a\n", base = null, remote = "# Theirs\n* b\n")
        assertFalse(result.hadConflict, "no ancestor -> union, not a conflict to tiebreak away")
        assertTrue(result.merged.contains("* a") && result.merged.contains("* b"),
            "both sides present: <<${result.merged}>>")
    }

    /** Identical content with no ancestor must not double up. */
    @Test
    fun nullBaseMergeOfIdenticalContentIsIdentity() {
        val content = "# Same\n* x\n"
        val result = ColumnMerge.mergeColumn(local = content, base = null, remote = content)
        assertFalse(result.hadConflict)
        kotlin.test.assertEquals(content, result.merged)
    }
}
