package tdz.model.localstorage

import kotlin.js.Json
import kotlin.js.json

/** Per remote-path sync bookkeeping: last seen Dropbox [rev] and last local edit time. */
data class SyncEntry(val rev: String?, val localUpdatedAt: Double)

/**
 * Singleton map of Dropbox path -> [SyncEntry]. Powers conflict detection: on push we send the
 * stored [SyncEntry.rev] for optimistic concurrency; on conflict we compare [SyncEntry.localUpdatedAt]
 * against the remote server-modified time.
 */
data class SyncStateDoc(val entries: Map<String, SyncEntry>) : HasKey {

    override val key: String get() = singletonKey()

    fun with(path: String, entry: SyncEntry) = SyncStateDoc(entries + (path to entry))
    fun without(path: String) = SyncStateDoc(entries - path)

    override fun toJson(): Json {
        val obj = json()
        entries.forEach { (path, e) ->
            obj[path] = json("rev" to e.rev, "localUpdatedAt" to e.localUpdatedAt)
        }
        return obj
    }

    companion object {
        fun singletonKey() = "state"

        val EMPTY = SyncStateDoc(emptyMap())

        fun fromJson(json: Json): SyncStateDoc {
            val map = mutableMapOf<String, SyncEntry>()
            val obj = json.asDynamic()
            val keys = js("Object").keys(obj) as Array<String>
            keys.forEach { path ->
                val e = obj[path]
                map[path] = SyncEntry(
                    rev = e["rev"] as String?,
                    localUpdatedAt = (e["localUpdatedAt"] as Number).toDouble(),
                )
            }
            return SyncStateDoc(map)
        }
    }
}
