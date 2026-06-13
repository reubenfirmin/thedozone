package tdz.sync

import kotlinx.browser.window
import kotlin.js.Date
import kotlin.js.json

/**
 * Pre-sync safety net: a snapshot of all app-owned localStorage keys, so a destructive reconcile is
 * recoverable. Snapshots live under [BACKUP_PREFIX]<id>; [META_KEY] holds the ordered list of ids
 * (oldest first). We keep the most recent [MAX_BACKUPS]. Backup keys and auth secrets are excluded so
 * a snapshot never captures tokens and never nests backups inside backups.
 */
object LocalBackup {

    const val MAX_BACKUPS = 3
    private const val BACKUP_PREFIX = "tdz.backup."
    private const val META_KEY = "tdz.backup.meta"
    // The single rolling "restore point" slot (see PeriodicBackup). Under BACKUP_PREFIX so it is
    // never captured into other backups.
    private const val AUTO_SLOT = "tdz.backup.auto"
    private const val AUTO_TS = "tdz.backup.auto.ts"
    // Auth secrets never belong in a data backup. Mirror the keys the auth modules persist
    // (DriveAuth.STORE_KEY, DropboxAuth's PKCE verifier).
    private val SECRET_KEYS = setOf("tdz.sync.driveToken", "tdz_dropbox_code_verifier")

    private var counter = 0

    /** All app-owned data as one JSON string, or null when there is nothing to back up. */
    private fun capture(): String? {
        tdz.model.localstorage.flushPendingWrites()   // an unflushed edit must not be missed
        val ls = window.localStorage
        val data = json()
        var count = 0
        for (i in 0 until ls.length) {
            val k = ls.key(i) ?: continue
            if (k.startsWith(BACKUP_PREFIX) || k in SECRET_KEYS) continue
            data[k] = ls.getItem(k); count++
        }
        return if (count == 0) null else JSON.stringify(data)
    }

    /** Capture all app data into a new backup slot; returns its id, or null if nothing to back up / failed. */
    fun snapshot(): String? {
        return try {
            val ls = window.localStorage
            val raw = capture() ?: return null
            val id = "${Date.now().toLong()}-${++counter}"
            ls.setItem("$BACKUP_PREFIX$id", raw)
            // Commit slot -> meta + prune. If this fails (e.g. QuotaExceeded after the big slot write),
            // delete the just-written orphan slot so it can't leak a full copy of app data.
            try {
                val ids = list() + id
                ls.setItem(META_KEY, JSON.stringify(ids.toTypedArray()))
                prune(ids)
            } catch (e: Throwable) {
                runCatching { ls.removeItem("$BACKUP_PREFIX$id") }
                throw e
            }
            id
        } catch (e: Throwable) {
            console.warn("[backup] snapshot failed: ${e.message}")
            null
        }
    }

    /** Ordered backup ids (oldest first). */
    fun list(): List<String> = runCatching {
        val raw = window.localStorage.getItem(META_KEY) ?: return emptyList()
        (JSON.parse<Array<String>>(raw)).toList()
    }.getOrDefault(emptyList())

    /**
     * Restore every key from backup [id] over current localStorage. Returns true if the slot existed
     * and was applied; false if the slot was missing or the restore failed (logged).
     */
    fun restore(id: String): Boolean = runCatching {
        val raw = window.localStorage.getItem("$BACKUP_PREFIX$id") ?: return false
        apply(raw)
        true
    }.getOrElse {
        console.warn("[backup] restore failed: ${it.message}")
        false
    }

    // ---- the rolling periodic slot (see PeriodicBackup) ----

    /** Refresh THE periodic backup slot (one slot, overwritten). False if nothing to back up / failed. */
    fun autoSnapshot(): Boolean = try {
        val raw = capture()
        if (raw == null) false else {
            window.localStorage.setItem(AUTO_SLOT, raw)
            window.localStorage.setItem(AUTO_TS, Date.now().toString())
            true
        }
    } catch (e: Throwable) {
        console.warn("[backup] auto snapshot failed: ${e.message}")
        false
    }

    /** When the periodic backup was taken (epoch ms), or null when none exists. */
    fun autoTimestamp(): Double? =
        window.localStorage.getItem(AUTO_TS)?.toDoubleOrNull()
            ?.takeIf { window.localStorage.getItem(AUTO_SLOT) != null }

    /** The backed-up value of one [key] in the periodic slot, or null (no slot / key absent). */
    fun autoValue(key: String): String? = runCatching {
        val raw = window.localStorage.getItem(AUTO_SLOT) ?: return null
        val v = JSON.parse<dynamic>(raw)[key]
        if (v == null || v == undefined) null else v as String
    }.getOrNull()

    /**
     * Restore ONLY [keys] from the periodic slot: each is replayed when the backup has it and
     * REMOVED when it doesn't (a true rollback of exactly that subset). Every other key — other
     * canvases, the sync bookkeeping, the theme — is untouched: restore is scoped to one board,
     * and the live sync vectors must survive so the restored content can be re-stamped as
     * causally newest and pushed up (replaying OLD bookkeeping would make the remote look ahead
     * and the next pull would undo the restore).
     */
    fun restoreAutoKeys(keys: Set<String>): Boolean = runCatching {
        val raw = window.localStorage.getItem(AUTO_SLOT) ?: return false
        tdz.model.localstorage.flushPendingWrites()   // a buffered write must not clobber the restore
        val obj = JSON.parse<dynamic>(raw)
        keys.forEach { k ->
            val v = obj[k]
            if (v == null || v == undefined) window.localStorage.removeItem(k)
            else window.localStorage.setItem(k, v as String)
        }
        true
    }.getOrElse {
        console.warn("[backup] scoped restore failed: ${it.message}")
        false
    }

    /** Replay [raw] over localStorage: remove app keys the snapshot didn't have (true rollback),
     *  then write the snapshot's keys — always keeping secrets and backup slots. */
    private fun apply(raw: String) {
        tdz.model.localstorage.flushPendingWrites()   // a buffered write must not clobber the restore
        val obj = JSON.parse<dynamic>(raw)
        val backupKeys = (js("Object").keys(obj) as Array<String>).toSet()
        val ls = window.localStorage
        val toRemove = mutableListOf<String>()
        for (i in 0 until ls.length) {
            val k = ls.key(i) ?: continue
            if (k.startsWith(BACKUP_PREFIX) || k in SECRET_KEYS) continue
            if (k !in backupKeys) toRemove += k
        }
        toRemove.forEach { ls.removeItem(it) }
        backupKeys.forEach { k ->
            val v = obj[k]
            if (v != null && v != undefined) ls.setItem(k, v as String)
        }
    }

    /** Convenience: the newest backup id, or null. */
    fun latest(): String? = list().lastOrNull()

    private fun prune(ids: List<String>) {
        val ls = window.localStorage
        if (ids.size <= MAX_BACKUPS) return
        val drop = ids.take(ids.size - MAX_BACKUPS)
        drop.forEach { ls.removeItem("$BACKUP_PREFIX$it") }
        ls.setItem(META_KEY, JSON.stringify(ids.drop(drop.size).toTypedArray()))
    }
}
