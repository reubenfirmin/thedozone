package tdz.view

import tdz.model.Direction
import tdz.model.Split
import web.dom.document
import web.events.EventType
import web.events.addEventListener
import web.events.removeEventListener
import web.keyboard.KeyboardEvent

/**
 * The operations a keyboard shortcut can trigger. Implemented by [Index], so the shortcut table
 * stays a pure binding→action map with no view/router/model knowledge of its own. Each returns
 * whether it actually did something (so a no-op binding doesn't `preventDefault` and swallow the key).
 */
interface ShortcutActions {
    fun toggleEditorMode(): Boolean
    fun navigateFocus(direction: Direction): Boolean
    fun splitFocusedColumn(split: Split): Boolean
    fun clearDone(): Boolean
    fun goToParentBoard(): Boolean
    fun openBoardPalette(): Boolean
    fun openHelpShortcuts(): Boolean
    fun openHelpFormatting(): Boolean
    fun closeOpenOverlayRoute(): Boolean
    fun isEditableFocused(): Boolean
}

/** A modifier requirement: [Yes] = must be held, [No] = must be absent, [Any] = don't care. */
private enum class Mod { Yes, No, Any }

/**
 * A typed key binding: the key(s) it fires on plus its modifier requirements — no scattered raw
 * `keyEvent.ctrlKey && keyEvent.key == ...` chains. [ctrlOrMeta] models the Ctrl-OR-Cmd shortcuts
 * (where either modifier triggers); when set, [ctrl]/[meta] are not checked individually.
 * [keyIgnoreCase] matches the key case-insensitively (so `L` and `l` both bind).
 */
private data class KeyChord(
    val keys: Set<String>,
    val ctrl: Mod = Mod.Any,
    val alt: Mod = Mod.Any,
    val meta: Mod = Mod.Any,
    val ctrlOrMeta: Boolean = false,
    val keyIgnoreCase: Boolean = false,
    val requireNotEditable: Boolean = false,
) {
    constructor(key: String, ctrl: Mod = Mod.Any, alt: Mod = Mod.Any, meta: Mod = Mod.Any,
                ctrlOrMeta: Boolean = false, keyIgnoreCase: Boolean = false, requireNotEditable: Boolean = false) :
        this(setOf(key), ctrl, alt, meta, ctrlOrMeta, keyIgnoreCase, requireNotEditable)

    fun matches(e: KeyboardEvent, editableFocused: Boolean): Boolean {
        val key = if (keyIgnoreCase) e.key.lowercase() else e.key
        if (key !in keys) return false
        if (requireNotEditable && editableFocused) return false
        if (ctrlOrMeta) {
            if (!(e.ctrlKey || e.metaKey)) return false
        } else {
            if (!ctrl.holds(e.ctrlKey) || !meta.holds(e.metaKey)) return false
        }
        return alt.holds(e.altKey)
    }

    private fun Mod.holds(present: Boolean) = when (this) {
        Mod.Yes -> present
        Mod.No -> !present
        Mod.Any -> true
    }
}

/**
 * The app's document-level keyboard shortcuts, expressed as a typed binding→action table. Installed
 * once for the app's life; the first matching binding whose action reports it handled the key wins
 * and the key is consumed ([KeyboardEvent.preventDefault]). All board/editor work goes through the
 * supplied [ShortcutActions], keeping this unit free of view/router internals.
 */
class KeyboardShortcuts(private val actions: ShortcutActions) {

    private val bindings: List<Pair<KeyChord, (KeyboardEvent) -> Boolean>> = listOf(
        // Ctrl+' toggles editor mode for the focused column. Plain `i` is the documented vim-style
        // alias — guarded by editable focus so it never hijacks typing.
        KeyChord("'", ctrl = Mod.Yes) to { _ -> actions.toggleEditorMode() },
        KeyChord("i", ctrl = Mod.No, alt = Mod.No, meta = Mod.No, requireNotEditable = true)
            to { _ -> actions.toggleEditorMode() },
        // Arrow keys (no modifiers) navigate between columns.
        KeyChord(ARROWS, ctrl = Mod.No, alt = Mod.No, meta = Mod.No)
            to { e -> arrowDirection(e.key)?.let { actions.navigateFocus(it) } ?: false },
        // Ctrl+Alt+arrow splits the focused column: left/right side by side, up/down stacked.
        KeyChord(ARROWS, ctrl = Mod.Yes, alt = Mod.Yes)
            to { e -> arrowSplit(e.key)?.let { actions.splitFocusedColumn(it) } ?: false },
        // Ctrl+; clears completed tasks from every card on the canvas.
        KeyChord(";", ctrl = Mod.Yes, alt = Mod.No) to { _ -> actions.clearDone() },
        // Leave paging to editable fields, including when CodeMirror reaches a document boundary.
        KeyChord("PageUp", requireNotEditable = true) to { _ -> actions.goToParentBoard() },
        KeyChord("PageDown", requireNotEditable = true) to { _ -> actions.openBoardPalette() },
        // Ctrl/Cmd+L and Ctrl/Cmd+K also open the board palette — L restores URL-style addressing in
        // the installed PWA window, K is the conventional command-palette key. Guarded by editable
        // focus so they never hijack the editor's own commands.
        KeyChord(setOf("l", "k"), ctrlOrMeta = true, keyIgnoreCase = true, requireNotEditable = true)
            to { _ -> actions.openBoardPalette() },
        // F1 / F2 open the corresponding help docs as in-app overlay routes.
        KeyChord("F1") to { _ -> actions.openHelpShortcuts() },
        KeyChord("F2") to { _ -> actions.openHelpFormatting() },
        // Escape closes an open overlay route (help/settings/privacy) by returning to the canvas.
        KeyChord("Escape") to { _ -> actions.closeOpenOverlayRoute() },
    )

    /** One active app shortcut handler, including when an Index is replaced. */
    fun install() {
        installedHandler?.let { document.removeEventListener(EventType<KeyboardEvent>("keydown"), it) }
        val handler: (KeyboardEvent) -> Unit = handler@{ event ->
            // CodeMirror handles keys on its content element; those events still bubble.
            if (event.defaultPrevented) return@handler
            val editable = actions.isEditableFocused()
            // First matching binding wins (mirroring the old if/else-if chain): its action runs and
            // its result alone decides whether the key is consumed. Later bindings never run.
            val handled = bindings.firstOrNull { (chord, _) -> chord.matches(event, editable) }
                ?.let { (_, action) -> action(event) } ?: false
            if (handled) event.preventDefault()
        }
        installedHandler = handler
        document.addEventListener(EventType<KeyboardEvent>("keydown"), handler)
    }

    private fun arrowDirection(key: String) = when (key) {
        "ArrowLeft" -> Direction.LEFT
        "ArrowRight" -> Direction.RIGHT
        "ArrowUp" -> Direction.UP
        "ArrowDown" -> Direction.DOWN
        else -> null
    }

    private fun arrowSplit(key: String) = when (key) {
        "ArrowLeft", "ArrowRight" -> Split.VERTICAL   // side by side
        "ArrowUp", "ArrowDown" -> Split.HORIZONTAL    // stacked
        else -> null
    }

    private companion object {
        var installedHandler: ((KeyboardEvent) -> Unit)? = null
        val ARROWS = setOf("ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown")
    }
}
