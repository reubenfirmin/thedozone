package tdz.sync

import kotlinx.browser.window
import kotlin.js.Date

/**
 * A stable, opaque id for THIS browser profile, used as the key for this device's component in every
 * column's version vector. Persisted in localStorage; if cleared, the profile simply becomes a new
 * device (a new vector component) — harmless. Not a secret, not synced.
 *
 * `get()` re-asserts the cached id into localStorage whenever the two diverge (e.g. localStorage was
 * cleared out from under us), keeping the in-memory and stored ids consistent.
 */
object DeviceId {
    private const val KEY = "tdz.sync.deviceId"
    private var cached: String? = null
    private var seq = 0

    fun get(): String {
        val stored = window.localStorage.getItem(KEY)

        // If we have a cached value, ensure localStorage is in sync (it may have been cleared).
        cached?.let { id ->
            if (stored != id) {
                // localStorage was cleared externally; re-establish our id there.
                window.localStorage.setItem(KEY, id)
            }
            return id
        }

        // No cached value yet. Use whatever is in localStorage, or generate a fresh id.
        if (stored != null && stored.isNotEmpty()) {
            cached = stored
            return stored
        }

        val fresh = "d-${Date.now().toLong()}-${seq++}-${Date.now().toLong() % 100000}"
        window.localStorage.setItem(KEY, fresh)
        cached = fresh
        return fresh
    }
}
