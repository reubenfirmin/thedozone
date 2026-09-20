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
        val b = lines(remote)
        // With no shared ancestor, an empty origin leaves diff3 unable to anchor on content common to
        // both sides: the whole column collapses into one conflict region and the union below would
        // duplicate every shared line (the reported bug — a locally-appended column merged against the
        // shorter pre-existing remote came back with its shared prefix doubled). Synthesize the
        // ancestor as the longest common subsequence of the two sides, so the shared lines become a
        // stable base (emitted once) and only each side's genuine additions remain to union.
        val o = if (noAncestor) longestCommonSubsequence(a, b) else lines(base)
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

    /** Longest common subsequence of two line arrays, order-preserving. Used as a synthesized 3-way
     *  base when no shared ancestor is known, so diff3 anchors on the lines common to both sides
     *  (emitting them once) instead of collapsing the column into one conflict region that would
     *  duplicate the shared content. O(n·m); columns are small. */
    private fun longestCommonSubsequence(a: Array<String>, b: Array<String>): Array<String> {
        if (a.isEmpty() || b.isEmpty()) return emptyArray()
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = ArrayList<String>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { out.add(a[i]); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return out.toTypedArray()
    }

    private fun dynLines(side: dynamic): Array<String> =
        if (side != null && side != undefined) side.unsafeCast<Array<String>>() else emptyArray()
}
