package tdz.view

import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.id
import tdz.EDITOR_TAB_SIZE
import tdz.controller.events.ColumnEdited
import tdz.controller.events.Event
import tdz.controller.events.ExitEditor
import tdz.controller.events.NavigateColumn
import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.Card
import tdz.model.Direction
import tdz.model.markdown.MarkdownService
import tdz.view.theme.ThemeManager
import web.html.HTMLElement
import zoned.framework.dom.Ref
import zoned.framework.dom.onMount
import zoned.framework.dom.ref
import zoned.framework.events.EventBus
import zoned.framework.interop.css
import zoned.framework.libs.CodeMirrorOptions
import zoned.framework.libs.CodeMirrorTheme
import zoned.framework.libs.CodeMirrorWrapper
import zoned.framework.libs.EditorCompletion
import zoned.framework.libs.EditorCompletions
import zoned.framework.libs.EditorPosition
import zoned.framework.libs.loadCodeMirror

/**
 * The column's Markdown editor. Zoned owns the engine; TDZ supplies list rules, board links,
 * navigation and persistence. [detach] saves and parks the live editor for the next [render].
 * [destroy] performs teardown when the canvas is disposed.
 */
class EditorColumn(
    val canvas: Canvas,
    val columnId: ColumnId,
    val eventBus: EventBus<Event>,
    /** Read at completion time so newly created or renamed boards are immediately available. */
    val boardNames: () -> List<CanvasName> = { emptyList() },
) {
    internal var editor: CodeMirrorWrapper? = null
        private set
    private var rootEl: HTMLElement? = null
    private var mountGeneration = 0
    private var discardOnDetach = false

    fun FlowContent.render(cards: List<Card>) {
        val text = MarkdownService.toMd(cards, false).let { if (it.isNotEmpty()) "$it\n" else it }
        val host = Ref<HTMLElement>()
        div {
            id = DomIds.editor(columnId)
            ref(host)
            css { width = 100.pct; height = 100.pct; overflow = Overflow.hidden }
            onMount {
                val generation = ++mountGeneration
                val fresh = host.element
                val parked = rootEl
                val existing = editor
                if (parked != null && existing != null) {
                    fresh.parentElement?.replaceChild(parked, fresh)
                    existing.setValue(text)
                    existing.setTheme(editorTheme())
                    existing.focus()
                    existing.resize()
                    return@onMount
                }

                loadCodeMirror().then { modules ->
                    // A chunk load must not resurrect a detached or replaced column.
                    if (generation != mountGeneration || !fresh.isConnected) return@then
                    val ed = CodeMirrorWrapper(modules, fresh, CodeMirrorOptions(
                        text = text,
                        tabSize = EDITOR_TAB_SIZE,
                        plain = ThemeManager.current.editorPlain,
                        // TDZ's empty lines separate cards; retain our own list continuation rules.
                        markdownKeymap = false,
                        theme = editorTheme(),
                    ))
                    rootEl = fresh
                    editor = ed
                    installListContinuation(ed)
                    installListIndent(ed)
                    installEditorShortcuts(ed)
                    installBoardLinkCompletion(ed)
                    ed.focus()
                    ed.resize()
                }
            }
        }
    }

    private fun editorTheme(): CodeMirrorTheme = ThemeManager.current.let { t ->
        CodeMirrorTheme(
            foreground = t.fg,
            // CanvasColumns owns the surface, including per-column colors and wallpaper.
            background = Color.transparent,
            popupBackground = t.menuBg,
            popupForeground = t.menuFg,
            muted = t.menuTitle,
            lineNumberColor = t.fg,
            accent = t.heading2,
            heading = t.heading1,
            link = t.link,
            selection = t.linkHoverBg,
            activeLine = t.taskHover,
            fontFamily = if (t.editorPlain) t.fontFamily else "monospace",
            dark = t.editorDark,
        )
    }

    /** Discard the unsaved buffer on the next detach (the "Undo edits" action). */
    fun revert() { discardOnDetach = true }

    fun detach() {
        mountGeneration++
        if (discardOnDetach) discardOnDetach = false else save()
        rootEl?.remove()
    }

    private fun installListContinuation(editor: CodeMirrorWrapper) {
        editor.addKeyBinding("Enter") { ed ->
            if (ed.hasSelection()) return@addKeyBinding false
            val pos = ed.getCursorPosition()
            val line = ed.getLine(pos.row)
            val info = if (pos.column >= line.length) listContinuation(line) else null
            when {
                info == null -> false
                info.content.isEmpty() -> {
                    ed.replace(EditorPosition(pos.row, 0), EditorPosition(pos.row, line.length), "", EditorPosition(pos.row, 0))
                    true
                }
                else -> { ed.insert("\n${info.indent}${info.nextMarker}"); true }
            }
        }
    }

    /** List items move as a whole; selected lines and non-list text use CodeMirror's indentation. */
    private fun installListIndent(editor: CodeMirrorWrapper) {
        val indent = " ".repeat(EDITOR_TAB_SIZE)
        editor.addKeyBinding("Tab") { ed ->
            if (ed.completionLabels().isNotEmpty()) {
                ed.acceptCompletion()
                return@addKeyBinding true
            }
            if (ed.hasSelection()) return@addKeyBinding false
            val pos = ed.getCursorPosition()
            if (listContinuation(ed.getLine(pos.row)) != null) {
                ed.replace(EditorPosition(pos.row, 0), EditorPosition(pos.row, 0), indent,
                    EditorPosition(pos.row, pos.column + indent.length))
                true
            } else false
        }
        editor.addKeyBinding("Shift-Tab") { ed ->
            if (ed.hasSelection()) return@addKeyBinding false
            val pos = ed.getCursorPosition()
            val line = ed.getLine(pos.row)
            if (listContinuation(line) != null && line.startsWith(indent)) {
                ed.replace(EditorPosition(pos.row, 0), EditorPosition(pos.row, indent.length), "",
                    EditorPosition(pos.row, maxOf(0, pos.column - indent.length)))
                true
            } else false
        }
    }

    private fun installEditorShortcuts(editor: CodeMirrorWrapper) {
        editor.addKeyBinding("Ctrl-ArrowUp") { jumpToHeading(it, forward = false) }
        editor.addKeyBinding("Ctrl-ArrowDown") { jumpToHeading(it, forward = true) }
        editor.addKeyBinding("Alt-ArrowLeft") { eventBus.fire(NavigateColumn(Direction.LEFT)); true }
        editor.addKeyBinding("Alt-ArrowRight") { eventBus.fire(NavigateColumn(Direction.RIGHT)); true }
        // CodeMirror's completion keymap gets Escape first, to dismiss its popup.
        editor.addKeyBinding("Escape") { eventBus.fire(ExitEditor(columnId)); true }
    }

    private fun installBoardLinkCompletion(editor: CodeMirrorWrapper) {
        editor.setCompletionSource { context ->
            val line = context.line
            val column = context.cursor.column
            val before = line.substring(0, column)
            val suggestions = boardLinkCompletions(before, boardNames(), canvas.name)
            if (suggestions.isEmpty()) null else {
                val closer = if (line.getOrNull(column) == ']') "" else "]"
                EditorCompletions(
                    fromColumn = before.lastIndexOf('[') + 1,
                    options = suggestions.map { s ->
                        EditorCompletion(s.leaf, "${s.absolutePath} (${s.leaf})$closer", s.breadcrumb.ifEmpty { "board" })
                    },
                )
            }
        }
    }

    private fun jumpToHeading(ed: CodeMirrorWrapper, forward: Boolean): Boolean {
        val target = headingRow(ed.getValue().split("\n"), ed.getCursorPosition().row, forward) ?: return false
        ed.moveCursorTo(target, 0)
        return true
    }

    fun focus() { editor?.focus() }

    fun save() {
        editor?.let { eventBus.fire(ColumnEdited(it.getValue(), canvas.name, columnId)) }
    }

    fun destroy() {
        mountGeneration++
        save()
        editor?.destroy()
        editor = null
        rootEl = null
    }
}

/** Board path to insert, human-visible leaf to match, and parent breadcrumb to display. */
internal data class BoardSuggestion(val absolutePath: String, val leaf: String, val breadcrumb: String)

/** Available boards inside an unclosed link; the editor filters these by the typed leaf prefix. */
internal fun boardLinkCompletions(beforeCursor: String, boards: List<CanvasName>, self: CanvasName): List<BoardSuggestion> {
    val open = beforeCursor.lastIndexOf('[')
    val close = beforeCursor.lastIndexOf(']')
    if (open < 0 || close > open) return emptyList()
    val selfPath = "/" + self.normalized()
    return boards
        .filter { it.normalized().isNotEmpty() }
        .map { BoardSuggestion(
            absolutePath = "/" + it.normalized(),
            leaf = it.leaf(),
            breadcrumb = it.parentName().display(),
        ) }
        .filter { it.absolutePath != selfPath }
        .distinctBy { it.absolutePath }
        .sortedBy { it.absolutePath }
}

/** Previous/next heading relative to [fromRow], or null if there is none in that direction. */
internal fun headingRow(lines: List<String>, fromRow: Int, forward: Boolean): Int? {
    val search = if (forward) (fromRow + 1 until lines.size) else (fromRow - 1 downTo 0)
    return search.firstOrNull { lines[it].trimStart().startsWith("#") }
}

internal data class ListContinuation(val indent: String, val nextMarker: String, val content: String)

private val bulletList = Regex("^([*+\\-])\\s+(.*)$")
private val numberedList = Regex("^(\\d+)\\.\\s+(.*)$")

/** Retain the bullet and indent, increment numbered lists, and exit on an empty item. */
internal fun listContinuation(line: String): ListContinuation? {
    val indent = line.takeWhile { it == ' ' || it == '\t' }
    val rest = line.substring(indent.length)
    bulletList.find(rest)?.let {
        return ListContinuation(indent, "${it.groupValues[1]} ", it.groupValues[2].trim())
    }
    numberedList.find(rest)?.let {
        val next = (it.groupValues[1].toIntOrNull() ?: 0) + 1
        return ListContinuation(indent, "$next. ", it.groupValues[2].trim())
    }
    return null
}
