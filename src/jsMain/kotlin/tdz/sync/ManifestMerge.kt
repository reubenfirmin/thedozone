package tdz.sync

import tdz.model.localstorage.CanvasDoc

/**
 * First-connect manifest union: with NO sync history for a canvas, neither manifest may silently
 * win (last-write-wins defaulted local to 0.0, so a stale remote always overwrote a fresh local
 * board, orphaning every local-only column). The remote manifest keeps its shape — name, parent,
 * layout tree — and every local-only CONTENT column (not in the remote, not a local layout
 * container) is appended, so its cards stay reachable as extra panes.
 */
object ManifestMerge {

    fun union(local: String, remote: String): String {
        val l = runCatching { CanvasDoc.decode(local) }.getOrNull() ?: return remote
        val r = runCatching { CanvasDoc.decode(remote) }.getOrNull() ?: return local
        val localLayoutIds = l.layouts.map { it.columnId }.toSet()
        val extras = l.columns.filter { it !in r.columns && it !in localLayoutIds }
        if (extras.isEmpty()) return remote
        return CanvasDoc(r.name, r.parentName, r.columns + extras, r.layouts).encode()
    }
}
