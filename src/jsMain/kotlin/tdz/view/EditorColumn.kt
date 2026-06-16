package tdz.view

import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.id
import tdz.ACE_TAB_SIZE
import tdz.controller.events.ColumnEdited
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.ExitEditor
import tdz.controller.events.NavigateColumn
import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.Card
import tdz.model.Direction
import tdz.model.markdown.MarkdownService
import zoned.framework.dom.onMount
import kotlinx.css.*
import kotlinx.css.properties.*
import zoned.framework.interop.css
import zoned.framework.interop.getHtmlElement
import zoned.framework.interop.important
import tdz.view.theme.themedStyleSheet
import zoned.framework.libs.AceEditor
import zoned.framework.libs.aceCommand
import zoned.framework.libs.aceCompleter
import zoned.framework.libs.aceCompletion
import zoned.framework.libs.aceEditorOptions
import zoned.framework.libs.aceRange
import zoned.framework.libs.loadAce
import zoned.framework.libs.loadAceLanguageTools
import tdz.view.theme.ThemeManager
import kotlin.js.RegExp

/**
 * Editor mode for a column using Ace editor.
 *
 * Kept ALIVE across mode toggles (Ace init — DOM build, tokenizer, mode load — is the app's
 * heaviest single interaction): [detach] saves and parks the live editor element, and the next
 * [render] re-adopts it, refreshing the buffer from the model. [destroy] is real teardown, called
 * only when the canvas render is disposed ([CanvasColumns.saveEditors]).
 */
class EditorColumn(
    val canvas: Canvas,
    val columnId: ColumnId,
    val eventBus: EventBus<Event>,
    /** Live source of existing board names for the `[link]` autocompleter (read at completion time
     *  so boards created/renamed mid-session are offered). Defaults to none (e.g. in tests). */
    val boardNames: () -> List<CanvasName> = { emptyList() },
) {

    private var aceEditor: AceEditor? = null
    /** The editor's root element, parked here across detach/re-adopt (Ace's DOM lives inside). */
    private var rootEl: web.html.HTMLElement? = null
    /** Set by "Undo edits": the next [detach] discards the buffer instead of saving it. */
    private var discardOnDetach = false
    /** Number of full Ace initializations — the reuse contract is that toggles keep this at 1. */
    internal var initCount = 0
        private set

    fun FlowContent.render(cards: List<Card>) {
        val editorId = DomIds.editor(columnId)
        val text = MarkdownService.toMd(cards, false).let {
            if (it.isNotEmpty()) "$it\n" else it
        }

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
                val parked = rootEl
                val existing = aceEditor
                if (parked != null && existing != null) {
                    // Re-adopt the parked editor: swap the freshly built (empty) div for the parked
                    // element with the live Ace DOM, refresh the buffer from the model (the column
                    // may have changed while in CARD mode), and resize to the new slot.
                    getHtmlElement(editorId)?.let { fresh ->
                        fresh.parentElement?.replaceChild(parked, fresh)
                    }
                    if (existing.getValue() != text) {
                        existing.setValue(text)
                        existing.clearSelection()
                    }
                    existing.focus()
                    existing.resize()
                    return@onMount
                }

                // Ace is code-split: nobody pays its load/parse until the first editor opens.
                loadAce().then { ace ->
                    // The chunk fetch yielded; bail if the column was re-rendered/toggled meanwhile.
                    val el = getHtmlElement(editorId) ?: return@then
                    initCount++
                    rootEl = el
                    val editor = ace.edit(editorId)
                    aceEditor = editor
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
                    installBoardLinkCompletion(editor)
                    ensureEditorMarkdownStyles()

                    editor.setValue(text)
                    editor.clearSelection()
                    editor.focus()
                    editor.resize()
                }
            }
        }
    }

    /** The current buffer text (test seam). */
    internal fun text(): String = aceEditor?.getValue() ?: ""

    /** True once the board-link completer is wired (language_tools loaded + options applied). Test seam. */
    internal fun completerInstalled(): Boolean = aceEditor?.completers?.isNotEmpty() == true

    /** Number of suggestions our completer last produced (-1 if never asked). Test seam. */
    internal var lastCompletionCount = -1
        private set

    /** Force the autocomplete popup with [textBefore] in the buffer and the caret at its end, so a
     *  test can confirm Ace actually invokes our completer end-to-end. Test seam. */
    internal fun triggerCompletionForTest(textBefore: String) {
        val ed = aceEditor ?: return
        ed.setValue(textBefore)
        ed.clearSelection()   // setValue selects all; drop the selection, leaving the caret at the end
        ed.execCommand("startAutocomplete")
    }

    /** Type [ch] through the command pipeline (as a keystroke would), exercising LIVE autocompletion
     *  — the path a real user hits, unlike the manual [triggerCompletionForTest]. Test seam. */
    internal fun typeForTest(ch: String) {
        val ed = aceEditor ?: return
        ed.commands.exec("insertstring", ed, ch)
    }

    /**
     * Discard the unsaved buffer ("Undo edits"): the upcoming [detach] skips its save, so the model
     * keeps the pre-edit content and the column re-renders from it. One-shot — cleared on detach.
     */
    fun revert() { discardOnDetach = true }

    /**
     * Save and PARK (not destroy): the root element is pulled out of the DOM before the column
     * rebuild discards its parent, keeping Ace's DOM alive for the next [render] to re-adopt. After
     * a [revert] the buffer is dropped instead of saved.
     */
    fun detach() {
        if (discardOnDetach) discardOnDetach = false else save()
        rootEl?.remove()
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

    /**
     * Suggest existing board names while the cursor sits inside an unclosed `[ ]` link. Ace's
     * autocompletion lives in the `language_tools` extension, so load it on demand (mirroring how
     * [loadAce] code-splits Ace itself), then register a single completer that replaces Ace's
     * default word/snippet completers — we only want board names here, not generic word completion.
     */
    private fun installBoardLinkCompletion(editor: AceEditor) {
        loadAceLanguageTools().then<Unit> {
            val completer = aceCompleter(
                // `[` opens the menu immediately, even before any name is typed (live autocompletion
                // otherwise needs a non-empty prefix, so a bare `[` would pop nothing).
                triggerCharacters = arrayOf("["),
                // Treat every non-bracket, non-newline char as part of the token, so a multi-word
                // board name ("My Other Canvas") is one prefix back to the opening `[` — without
                // this, Ace's default word tokenizer would cut the prefix at the first space.
                identifierRegexps = arrayOf(RegExp("[^\\[\\]\\r\\n]")),
                getCompletions = { _, session, pos, _, callback ->
                    val before = session.getLine(pos.row).substring(0, pos.column)
                    val suggestions = boardLinkCompletions(before, boardNames(), canvas.name)
                    lastCompletionCount = suggestions.size
                    callback(null, suggestions.map { s ->
                        // caption = leaf so Ace's prefix-matching works on the board name; meta =
                        // breadcrumb shows the parent path as a grey hint. value inserts the
                        // absolute link text including the closing ] so the buffer becomes [/a/x (x)].
                        val insertValue = "${s.absolutePath} (${s.leaf})]"
                        aceCompletion(caption = s.leaf, value = insertValue, meta = s.breadcrumb.ifEmpty { "board" })
                    }.toTypedArray())
                },
            )
            // Order matters: setOptions seeds Ace's default completers, so overwrite AFTER it.
            editor.setOptions(aceEditorOptions(enableBasicAutocompletion = true, enableLiveAutocompletion = true))
            editor.completers = arrayOf(completer)
        }
    }

    /** Move the cursor to the previous/next heading line; false (Ace default) if there is none. */
    private fun jumpToHeading(ed: AceEditor, forward: Boolean): Boolean {
        val target = headingRow(ed.getValue().split("\n"), ed.getCursorPosition().row, forward) ?: return false
        ed.moveCursorTo(target, 0)
        ed.clearSelection()
        return true
    }

    /**
     * Cobalt (like many Ace themes) colors markdown tokens only subtly. Inject theme-driven colors
     * for the editor's markdown tokens so the editor reads like the card view. Scoped to .ace_editor,
     * refreshed so a theme switch updates it.
     */
    private fun ensureEditorMarkdownStyles() {
        val t = ThemeManager.current
        themedStyleSheet("tdz-editor-md") {
            // !important throughout: Ace's own theme stylesheet wins specificity otherwise.
            rule(".ace_editor .ace_markup.ace_heading, .ace_editor .ace_heading") {
                important("color", t.heading1)
                important("background-color", "transparent")
                fontWeight = FontWeight.w700
            }
            rule(".ace_editor .ace_markup.ace_list, .ace_editor .ace_list") {
                important("color", t.heading2)
                important("background-color", "transparent")
            }
            rule(".ace_editor .ace_string.ace_strong") {
                important("color", t.heading2)
                fontWeight = FontWeight.w700
            }
            rule(".ace_editor .ace_string.ace_emphasis") {
                fontStyle = FontStyle.italic
            }
            rule(".ace_editor .ace_markup.ace_underline") {
                important("color", t.link)
                textDecoration = TextDecoration(setOf(TextDecorationLine.underline))
            }
            rule(".ace_editor .ace_string.ace_blockquote") {
                important("color", t.menuTitle)
                fontStyle = FontStyle.italic
            }
        }
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
        // Idempotent: beforeRemoval can run more than once (column re-render + canvas dispose);
        // a second destroy/save against the torn-down Ace instance must be a no-op.
        aceEditor = null
        rootEl = null
    }
}

/**
 * A single board suggested by the board-link completer.
 *
 * @param absolutePath  The board's absolute path, always starting with `/` (e.g. `/a/x`). Inserted
 *                      into the link text so the link resolves unambiguously regardless of the
 *                      editing board's location.
 * @param leaf          The last path segment in display form (e.g. `x`). Used as the completion
 *                      caption so Ace's prefix-matching filters on the human-visible name.
 * @param breadcrumb    The parent path in display form (e.g. `a`). Shown as a grey hint in the
 *                      popup so boards with the same leaf are distinguishable. Empty for top-level boards.
 */
internal data class BoardSuggestion(val absolutePath: String, val leaf: String, val breadcrumb: String)

/**
 * Board-link suggestions for the text [beforeCursor] (the line up to the caret): the existing
 * [boards] to offer when the caret sits inside an unclosed `[ ]`, minus [self] (no self-links),
 * de-duplicated by absolute path and sorted. Empty when the caret isn't inside a bracket link. Ace
 * narrows this list by the typed prefix (matching against caption = leaf); this only decides whether
 * to offer anything and what pool.
 */
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
