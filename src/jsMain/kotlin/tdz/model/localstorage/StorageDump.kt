package tdz.model.localstorage

import kotlinx.browser.window
import zoned.framework.Debugger

/**
 * Diagnostic: decode every localStorage key into something recognizable — a column shows its canvas
 * + a preview of its actual card text, a manifest shows its column list and layout tree, backups
 * show size. Gated by debug mode (Debugger.debug); one decoded multi-line message, not one call
 * per key. Read-only.
 */
object StorageDump {

    private const val UNMANAGED = "UNMANAGED?"
    private const val AUTH_SECRET = "auth-secret"
    private val COLUMN = Namespace.COLUMN.toString()
    private val COLUMN_MODE = Namespace.COLUMN_MODE.toString()
    private val NEWCANVAS = Namespace.NEWCANVAS.toString()

    fun dump() {
        if (!Debugger.debug) return   // gated by debug mode
        val ls = window.localStorage
        data class Row(val bytes: Int, val cat: String, val desc: String)
        val rows = mutableListOf<Row>()
        var total = 0
        for (i in 0 until ls.length) {
            val k = ls.key(i) ?: continue
            val v = ls.getItem(k) ?: ""
            total += k.length + v.length
            rows.add(Row(k.length + v.length, category(k), describe(k, v)))
        }
        rows.sortByDescending { it.bytes }

        val sb = StringBuilder("[storage] ${rows.size} keys, $total bytes total\n")
        rows.groupBy { it.cat }
            .map { (cat, rs) -> Triple(cat, rs.size, rs.sumOf { it.bytes }) }
            .sortedByDescending { it.third }
            .forEach { (cat, n, b) -> sb.append("  $cat — $n keys, $b bytes\n") }
        sb.append("  --- largest first ---\n")
        rows.forEach { sb.append("  ${it.bytes.toString().padStart(6)} B  ${it.desc}\n") }
        console.log(sb.toString())
    }

    private fun category(key: String): String = when {
        key.startsWith("tdz.backup.") -> "backup"
        key == "tdz.sync.deviceId" -> "deviceId"
        key == "tdz.sync.driveToken" || key == "tdz_dropbox_code_verifier" -> AUTH_SECRET
        key.startsWith(COLUMN_MODE) -> "column-mode"
        key.startsWith(COLUMN) -> "column"
        key.startsWith(NEWCANVAS) -> "canvas"
        key.startsWith(Namespace.SYNC_CONFIG.toString()) -> "sync-config"
        key.startsWith(Namespace.SYNC_STATE.toString()) -> "sync-state"
        key.startsWith(Namespace.THEME.toString()) -> "theme"
        else -> UNMANAGED
    }

    private fun describe(key: String, value: String): String = when {
        key.startsWith(COLUMN_MODE) -> "column-mode  $key = $value"
        key.startsWith(COLUMN) -> runCatching {
            val doc = ColumnDoc.decode(value)
            "column   '${doc.canvasName.normalized()}' / col ${doc.columnLabel}   →  ${preview(doc.value)}"
        }.getOrElse { "column   (undecodable) $key  ${preview(value)}" }
        key.startsWith(NEWCANVAS) -> runCatching {
            val d = CanvasDoc.decode(value)
            val layout = d.layouts.joinToString("; ") { "${it.columnId.value}${it.split.name.first()}→${it.contains.map { c -> c.value }}" }
            "canvas   '${d.name.normalized()}'  cols=${d.columns.map { it.value }}  layout=[$layout]"
        }.getOrElse { "canvas   (undecodable) $key  ${preview(value)}" }
        key.startsWith("tdz.backup") -> "backup   $key  (${value.length} chars)"
        // NEVER print an auth secret's value, even in a debug dump — only its length.
        category(key) == AUTH_SECRET -> "$AUTH_SECRET  $key  (redacted, ${value.length} chars)"
        category(key) == UNMANAGED -> "UNMANAGED?  $key  ⟪${preview(value)}⟫"
        else -> "$key  ${preview(value)}"
    }

    private fun preview(s: String): String =
        s.replace("\n", " ↵ ").take(120).let { if (s.length > 120) "$it…" else it }
}
