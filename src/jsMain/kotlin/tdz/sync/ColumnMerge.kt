package tdz.sync

/**
 * Outcome of a 3-way column merge.
 * @property merged best-effort merged markdown. When [hadConflict] is true this reflects only the
 *   LOCAL side of conflicting regions (remote edits in those regions are dropped); the caller must
 *   treat it as non-canonical and fall back to last-write-wins rather than persisting it blindly.
 * @property hadConflict true if any region had a genuine same-region overlap.
 */
data class MergeResult(val merged: String, val hadConflict: Boolean)

/**
 * 3-way line merge of a column's markdown using a common-ancestor [base]. Edits to different regions
 * (e.g. different cards) merge cleanly; edits to the same lines flag [MergeResult.hadConflict] and the
 * caller falls back to last-write-wins (backing up the loser). A null [base] (no shared ancestor, e.g.
 * first connect) is treated as an empty base, which unions additions from both sides.
 */
object ColumnMerge {

    fun mergeColumn(local: String, base: String?, remote: String): MergeResult {
        val trailingNewline = local.endsWith("\n")
        val noAncestor = base.isNullOrEmpty()
        val a = lines(local)
        val o = lines(base ?: "")
        val b = lines(remote)
        val regions = diff3Merge(a, o, b)
        val out = StringBuilder()
        var conflict = false
        for (region in regions) {
            val ok = region.ok
            if (ok != null && ok != undefined) {
                (ok as Array<String>).forEach { out.append(it).append('\n') }
            } else {
                val confA = dynLines(region.conflict?.a)
                val confB = dynLines(region.conflict?.b)
                if (noAncestor) {
                    // No shared ancestor (first connect / never-synced content): an "overlap" here
                    // is just both sides' content occupying the same position — neither derives
                    // from a common original, so neither may be chosen over the other. Keep BOTH
                    // (local first), deduping identical sides; this is the documented union, not
                    // a conflict to tiebreak away.
                    confA.forEach { out.append(it).append('\n') }
                    if (!confA.contentEquals(confB)) confB.forEach { out.append(it).append('\n') }
                } else {
                    conflict = true
                    // Keep local's side so local content is never silently lost; conflict is
                    // reported so the caller applies LWW + backup rather than trusting this body
                    // as canonical.
                    confA.forEach { out.append(it).append('\n') }
                }
            }
        }
        // Reconstruct the original termination: our per-line append always adds '\n', so drop the final
        // one when the source had no trailing newline. Keeps merged content byte-identical to inputs that
        // didn't change, so a clean merge never produces a spurious diff on the next sync.
        val result = if (trailingNewline) out.toString() else out.toString().trimEnd('\n')
        return MergeResult(result, conflict)
    }

    private fun lines(s: String): Array<String> =
        if (s.isEmpty()) emptyArray() else s.removeSuffix("\n").split("\n").toTypedArray()

    private fun dynLines(side: dynamic): Array<String> =
        if (side != null && side != undefined) side.unsafeCast<Array<String>>() else emptyArray()
}
