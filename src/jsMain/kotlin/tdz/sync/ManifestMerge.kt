package tdz.sync

import tdz.model.localstorage.CanvasDoc

/**
 * Concurrent/no-history layout merge. When neither side's structure causally dominates (a fresh
 * connect with edits on both sides, or genuinely concurrent offline layout edits), neither layout
 * may silently replace the other. We keep the LOCAL layout tree intact — so the board the user is
 * looking at is never clobbered — and PLACE every remote-only content column into the local root so
 * its cards stay reachable as extra panes (never orphaned/unplaced, which is the bug the old
 * "remote shape wins" union caused). Causally-decided cases (one side newer) are handled by the
 * engine and never reach here.
 */
object ManifestMerge {

    fun mergeLayouts(local: String, remote: String): String {
        val l = runCatching { CanvasDoc.decode(local) }.getOrNull() ?: return remote
        val r = runCatching { CanvasDoc.decode(remote) }.getOrNull() ?: return local
        val remoteLayoutIds = r.layouts.map { it.columnId }.toSet()
        // Remote CONTENT columns (not remote layout containers) that local doesn't already have.
        val extras = r.columns.filter { it !in l.columns && it !in remoteLayoutIds }
        if (extras.isEmpty()) return local   // local already covers everything → keep local as-is
        val rootId = l.columns.firstOrNull() ?: return remote
        // Append the extras into the LOCAL root layout so they render as additional panes.
        val mergedLayouts = l.layouts.map { spec ->
            if (spec.columnId == rootId) spec.copy(contains = spec.contains + extras) else spec
        }
        return CanvasDoc(l.name, l.parentName, l.columns + extras, mergedLayouts).encode()
    }
}
