package tdz.model.localstorage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import tdz.sync.VersionVector

/** Per remote-path sync bookkeeping: last seen [rev], last local edit time, and the last-synced
 *  content ([base]) used as the common ancestor for 3-way column merges (null until first sync). */
@Serializable
data class SyncEntry(
    val rev: String? = null,
    val localUpdatedAt: Double,
    val base: String? = null,
    val vv: VersionVector = emptyMap(),
    /** A TOMBSTONE: this column path was deleted; [vv] is its content vector at deletion. The entry is
     *  kept (not dropped) so the deletion travels in the manifest's `deleted` set and a concurrent
     *  stale re-add can't resurrect it. Tombstones carry no [rev]/[base] and have no local content. */
    val tombstone: Boolean = false,
)

/**
 * Singleton map of Dropbox path -> [SyncEntry]. Powers conflict detection: on push we send the
 * stored [SyncEntry.rev] for optimistic concurrency; on conflict we compare [SyncEntry.localUpdatedAt]
 * against the remote server-modified time.
 */
data class SyncStateDoc(val entries: Map<String, SyncEntry>) : HasKey {

    override val key: String get() = singletonKey()

    fun with(path: String, entry: SyncEntry) = SyncStateDoc(entries + (path to entry))
    fun without(path: String) = SyncStateDoc(entries - path)

    override fun encode(): String = storageJson.encodeToString(entries)

    companion object {
        fun singletonKey() = "state"

        val EMPTY = SyncStateDoc(emptyMap())

        fun decode(raw: String) = SyncStateDoc(storageJson.decodeFromString<Map<String, SyncEntry>>(raw))
    }
}
