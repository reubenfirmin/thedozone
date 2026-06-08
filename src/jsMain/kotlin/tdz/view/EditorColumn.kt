package tdz.view

import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.id
import tdz.ACE_TAB_SIZE
import tdz.controller.events.ColumnEdited
import tdz.controller.events.EventBus
import tdz.controller.events.ExitEditor
import tdz.controller.events.NavigateColumn
import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.Direction
import tdz.model.markdown.MarkdownService
import web.dom.ElementId
import web.dom.document
import web.html.HTMLStyleElement
import zoned.framework.dom.onMount
import kotlinx.css.*
import zoned.framework.interop.css
import zoned.framework.interop.getHtmlElement
import zoned.framework.libs.Ace
import zoned.framework.libs.AceEditor
import zoned.framework.libs.aceCommand
import zoned.framework.libs.aceRange
import zoned.framework.libs.ensureAceResolver
import tdz.view.theme.ThemeManager

/**
 * Editor mode for a column using Ace editor
 */
class EditorColumn(
    val canvas: Canvas,
    val cards: List<Card>,
    val columnId: Int,
    val eventBus: EventBus
) {

    private var aceEditor: AceEditor? = null

    fun FlowContent.render() {
        val editorId = "editor-$columnId"

        div {
            id = editorId
            // Constrain editor to column height
            css {
                width = 100.pct
                height = 100.pct
                overflow = Overflow.hidden
            }

            // Setup Ace editor after DOM is ready
            onMount {
                ensureAceResolver()   // wire webpack-bundled theme/mode/worker URLs before edit
                aceEditor = Ace.edit(editorId)
                aceEditor?.let { editor ->
                    // Configure editor
                    editor.setTheme(ThemeManager.current.editorTheme)
                    editor.setFontSize(16)
                    editor.getSession().apply {
                        setMode("ace/mode/markdown")
                        setUseWrapMode(true)
                        setTabSize(ACE_TAB_SIZE)
                        setUseSoftTabs(true)
                    }
                    installListContinuation(editor)
                    installListIndent(editor)
                    installEditorShortcuts(editor)
                    ensureEditorMarkdownStyles()

                    // Load content from cards
                    val text = MarkdownService.toMd(cards, false).let {
                        if (it.isNotEmpty()) "$it\n" else it
                    }
                    editor.setValue(text)
                    editor.clearSelection()
                    editor.focus()
                    editor.resize()
                }
            }
        }
    }

    /**
     * Smart list typing: pressing Enter at the end of a list item continues the list — inserts a
     * newline plus the same bullet (or the next number), with the item's indent. On an empty item
     * it exits the list (drops the bullet). Non-list lines fall through to the default Enter.
     */
    private fun installListContinuation(editor: AceEditor) {
        editor.commands.addCommand(aceCommand("tdz-list-continue", "Enter", "Enter") { ed ->
            val session = ed.getSession()
            val pos = ed.getCursorPosition()
            val line = session.getLine(pos.row)
            val info = if (pos.column >= line.length) listContinuation(line) else null
            when {
                info == null -> false   // not at the end of a list item -> default Enter
                info.content.isEmpty() -> {
                    // empty item -> exit the list: drop the bullet, stay on the now-blank line
                    session.replace(aceRange(pos.row, 0, pos.row, line.length), "")
                    ed.moveCursorTo(pos.row, 0)
                    true
                }
                else -> {
                    ed.insert("\n${info.indent}${info.nextMarker}")
                    true
                }
            }
        })
    }

    /**
     * Tab indents a list item by one level (prepends [ACE_TAB_SIZE] spaces); Shift-Tab outdents.
     * On non-list lines, falls through to Ace's default Tab handling.
     */
    private fun installListIndent(editor: AceEditor) {
        val indent = " ".repeat(ACE_TAB_SIZE)
        editor.commands.addCommand(aceCommand("tdz-list-indent", "Tab", "Tab") { ed ->
            val session = ed.getSession()
            val pos = ed.getCursorPosition()
            if (listContinuation(session.getLine(pos.row)) != null) {
                session.replace(aceRange(pos.row, 0, pos.row, 0), indent)
                ed.moveCursorTo(pos.row, pos.column + indent.length)
                true
            } else false
        })
        editor.commands.addCommand(aceCommand("tdz-list-outdent", "Shift-Tab", "Shift-Tab") { ed ->
            val session = ed.getSession()
            val pos = ed.getCursorPosition()
            val line = session.getLine(pos.row)
            if (listContinuation(line) != null && line.startsWith(indent)) {
                session.replace(aceRange(pos.row, 0, pos.row, indent.length), "")
                ed.moveCursorTo(pos.row, maxOf(0, pos.column - indent.length))
                true
            } else false
        })
    }

    /**
     * Editor-only navigation: Ctrl+Up/Down jump between markdown headings; Alt+Left/Right move
     * column focus; Esc returns the column to card mode. Column focus and mode live outside the
     * editor, so those go through the event bus.
     */
    private fun installEditorShortcuts(editor: AceEditor) {
        editor.commands.addCommand(aceCommand("tdz-prev-heading", "Ctrl-Up", "Ctrl-Up") { ed ->
            jumpToHeading(ed, forward = false)
        })
        editor.commands.addCommand(aceCommand("tdz-next-heading", "Ctrl-Down", "Ctrl-Down") { ed ->
            jumpToHeading(ed, forward = true)
        })
        editor.commands.addCommand(aceCommand("tdz-focus-prev-col", "Alt-Left", "Alt-Left") { _ ->
            eventBus.fire(NavigateColumn(Direction.LEFT)); true
        })
        editor.commands.addCommand(aceCommand("tdz-focus-next-col", "Alt-Right", "Alt-Right") { _ ->
            eventBus.fire(NavigateColumn(Direction.RIGHT)); true
        })
        editor.commands.addCommand(aceCommand("tdz-exit-editor", "Esc", "Esc") { _ ->
            eventBus.fire(ExitEditor(columnId)); true
        })
    }

    /** Move the cursor to the previous/next heading line; false (Ace default) if there is none. */
    private fun jumpToHeading(ed: AceEditor, forward: Boolean): Boolean {
        val target = headingRow(ed.getValue().split("\n"), ed.getCursorPosition().row, forward) ?: return false
        ed.moveCursorTo(target, 0)
        ed.clearSelection()
        return true
    }

    /**
     * Cobalt (like many Ace themes) colours markdown tokens only subtly. Inject theme-driven colours
     * for the editor's markdown tokens so the editor reads like the card view. Scoped to .ace_editor,
     * refreshed so a theme switch updates it.
     */
    private fun ensureEditorMarkdownStyles() {
        val t = ThemeManager.current
        val css = """
            .ace_editor .ace_markup.ace_heading, .ace_editor .ace_heading { color: ${t.heading1} !important; background-color: transparent !important; font-weight: 700; }
            .ace_editor .ace_markup.ace_list, .ace_editor .ace_list { color: ${t.heading2} !important; background-color: transparent !important; }
            .ace_editor .ace_string.ace_strong { color: ${t.heading2} !important; font-weight: 700; }
            .ace_editor .ace_string.ace_emphasis { font-style: italic; }
            .ace_editor .ace_markup.ace_underline { color: ${t.link} !important; text-decoration: underline; }
            .ace_editor .ace_string.ace_blockquote { color: ${t.menuTitle} !important; font-style: italic; }
        """.trimIndent()
        val style = (getHtmlElement("tdz-editor-md") as? HTMLStyleElement)
            ?: (document.createElement("style") as HTMLStyleElement).also {
                it.id = ElementId("tdz-editor-md")
                document.head.appendChild(it)
            }
        style.textContent = css
    }

    /** Focus the editor (e.g. after the column is repositioned in the DOM, which blurs it). */
    fun focus() {
        aceEditor?.focus()
    }

    fun save() {
        aceEditor?.let { editor ->
            val value = editor.getValue()
            eventBus.fire(ColumnEdited(value, canvas.name, columnId))
        }
    }

    fun destroy() {
        save()
        aceEditor?.destroy()
    }
}

/** Row of the previous (forward=false) or next (forward=true) markdown heading relative to
 *  [fromRow], or null if there is none in that direction. A heading line starts with `#`. */
internal fun headingRow(lines: List<String>, fromRow: Int, forward: Boolean): Int? {
    val search = if (forward) (fromRow + 1 until lines.size) else (fromRow - 1 downTo 0)
    return search.firstOrNull { lines[it].trimStart().startsWith("#") }
}

/** What to insert on the next line to continue [line]'s list. */
internal data class ListContinuation(val indent: String, val nextMarker: String, val content: String)

private val bulletList = Regex("^([*+\\-])\\s+(.*)$")
private val numberedList = Regex("^(\\d+)\\.\\s+(.*)$")

/**
 * If [line] is a list item, describe how to continue it: its leading [indent], the [nextMarker]
 * to repeat (same bullet, or the next number), and the item's [content] (empty => an empty item).
 * Returns null if [line] is not a list item.
 */
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
