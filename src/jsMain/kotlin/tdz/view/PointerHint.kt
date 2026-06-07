package tdz.view

import kotlinx.browser.window
import kotlin.math.abs
import kotlin.math.floor

/**
 * Best-effort pointing-device hint. No browser API reliably distinguishes a trackpad from a mouse, so
 * we infer it from wheel events: trackpads emit frequent, small and/or fractional pixel deltas (and
 * often a horizontal [deltaX] from two-finger swipes), whereas mouse wheels emit chunky integer steps
 * or line-mode deltas. The guess starts [Kind.UNKNOWN] and may upgrade after the first scroll gesture;
 * registered [onChange] listeners are notified so dependent UI (the intro card's instruction) updates.
 * OS detection ([isMac]) is separate and reliable.
 */
object PointerHint {

    enum class Kind { UNKNOWN, MOUSE, TRACKPAD }

    var kind: Kind = Kind.UNKNOWN
        private set

    val isMac: Boolean by lazy {
        val plat = window.navigator.asDynamic().platform.unsafeCast<String?>() ?: ""
        (window.navigator.userAgent + " " + plat).contains("Mac", ignoreCase = true)
    }

    private val listeners = mutableListOf<() -> Unit>()
    private var started = false

    /** Begin listening for wheel events (idempotent). */
    fun start() {
        if (started) return
        started = true
        val opts: dynamic = js("({ passive: true })")
        window.asDynamic().addEventListener("wheel", { e: dynamic -> onWheel(e) }, opts)
    }

    /** Register a callback fired whenever [kind] changes. */
    fun onChange(cb: () -> Unit) { listeners += cb }

    private fun onWheel(e: dynamic) {
        val guess = classify(
            deltaMode = e.deltaMode.unsafeCast<Int>(),
            dy = e.deltaY.unsafeCast<Double>(),
            dx = e.deltaX.unsafeCast<Double>(),
        )
        if (guess != Kind.UNKNOWN && guess != kind) {
            kind = guess
            listeners.forEach { it() }
        }
    }

    private fun classify(deltaMode: Int, dy: Double, dx: Double): Kind = when {
        deltaMode != 0 -> Kind.MOUSE                 // line/page mode → classic mouse wheel
        dx != 0.0 -> Kind.TRACKPAD                   // two-finger horizontal scroll
        abs(dy) in 0.0001..39.9999 -> Kind.TRACKPAD  // small, smooth steps
        dy != floor(dy) -> Kind.TRACKPAD             // fractional delta
        abs(dy) >= 100.0 -> Kind.MOUSE               // chunky integer steps
        else -> Kind.UNKNOWN
    }
}
