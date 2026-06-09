package tdz.sync

import kotlin.js.Json
import kotlin.js.json

/** A version vector: deviceId -> number of edits that device applied to a column. Absent == 0. */
typealias VersionVector = Map<String, Long>

enum class Causal { EQUAL, LOCAL_DOMINATES, REMOTE_DOMINATES, CONCURRENT }

/** Causal relationship of [a] (local) to [b] (remote). CONCURRENT = each has edits the other lacks. */
fun compareVv(a: VersionVector, b: VersionVector): Causal {
    var aBigger = false; var bBigger = false
    for (k in a.keys + b.keys) {
        val av = a[k] ?: 0L; val bv = b[k] ?: 0L
        if (av > bv) aBigger = true
        if (bv > av) bBigger = true
    }
    return when {
        aBigger && bBigger -> Causal.CONCURRENT
        aBigger            -> Causal.LOCAL_DOMINATES
        bBigger            -> Causal.REMOTE_DOMINATES
        else               -> Causal.EQUAL
    }
}

// NOTE: a vector accumulates one component per device that has ever edited the column; there is no
// pruning of retired devices yet (deferred — fine for personal/few-device use; revisit if vectors bloat).
/** The join (least upper bound): max of each component. Dominates both inputs. */
fun pointwiseMax(a: VersionVector, b: VersionVector): VersionVector {
    val out = HashMap<String, Long>()
    for (k in a.keys + b.keys) out[k] = maxOf(a[k] ?: 0L, b[k] ?: 0L)
    return out
}

/** Increment [self]'s component (a local edit/event by this device). */
fun bump(vv: VersionVector, self: String): VersionVector =
    vv + (self to ((vv[self] ?: 0L) + 1L))

fun vvToJson(vv: VersionVector): Json {
    val o = json()
    vv.forEach { (k, v) -> o[k] = v.toDouble() }   // JSON numbers are doubles in JS
    return o
}

fun vvFromJson(j: Json): VersionVector {
    val out = HashMap<String, Long>()
    val keys = js("Object").keys(j) as Array<String>
    keys.forEach { k -> out[k] = (j[k] as Number).toLong() }
    return out
}
