package tdz

import kotlinx.browser.window
import kotlin.js.Date
import kotlin.js.Promise

/**
 * Poll until [predicate] holds — for asserting on work that completes after an async boundary
 * (code-split chunk loads, deferred paints). Return the Promise from the @Test function; the JS
 * test runner awaits it. Rejects (failing the test) after [timeoutMs].
 */
fun pollUntil(what: String, timeoutMs: Int = 8000, predicate: () -> Boolean): Promise<Unit> =
    Promise { resolve, reject ->
        val start = Date.now()
        fun tick() {
            when {
                predicate() -> resolve(Unit)
                Date.now() - start > timeoutMs -> reject(Throwable("timed out waiting for $what"))
                else -> { window.setTimeout({ tick() }, 25) }
            }
        }
        tick()
    }
