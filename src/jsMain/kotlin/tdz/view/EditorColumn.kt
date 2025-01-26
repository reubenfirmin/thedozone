package tdz.view

import kotlinx.css.height
import kotlinx.css.pct
import tdz.model.*
import kotlinx.css.px
import kotlinx.css.width
import kotlinx.html.FlowContent
import kotlinx.html.div
import org.w3c.dom.events.KeyboardEvent
import tdz.model.markdown.MarkdownService
import zoned.framework.dom.ensureId
import zoned.framework.dom.onMount
import zoned.framework.interop.css
import zoned.framework.interop.el
import zoned.framework.libs.AceHelper.Companion.range
import zoned.framework.libs.AceWrapper

const val ACE_TAB_SIZE = 2

class EditorColumn(val canvasName: CanvasName,
                   private val columnWidth: Int,
                   val columnId: ColumnId,
//                   val eventBus: EventBus,
//                   dragDrop: DragDrop,
                   val cursorPosition: AceWrapper.AceCursorPosition?,
//                   afterRender: (Column) -> Unit
) {

    private var aceEditor: AceWrapper.AceEditor? = null

    fun cursorPos() = aceEditor?.getCursorPosition()

    fun FlowContent.render(cards: List<Card>) {
        div("h-full bg-red-400 flex-grow") {
            val divId = ensureId()
            css {
                width = this@EditorColumn.columnWidth.px
//                height = 100.pct
            }

            onMount {
                aceEditor = AceWrapper.edit(divId)
                aceEditor!!.let { aceEdit ->
                    //aceEdit.setTheme(theme.editorTheme)
                    aceEdit.setFontSize(18)
                    aceEdit.on("change") { delta ->
                        // if we add a tab, check whether the start of the line is a bullet, and indent if so
                        if (delta.action == "insert" && delta.lines.size == 1) {
                            if (delta.lines[0] == "  " && delta.start.row == delta.end.row) {
                                val row = delta.start.row
                                val line = aceEdit.getSession().getLine(row)
                                val trimmed = line.trim()
                                val firstChar = trimmed.firstOrNull()
                                // TODO valid list chars
                                // TODO clean up
                                if (firstChar != null) {
                                    if (firstChar == '*' || firstChar == '.' || firstChar == '-' || firstChar == '+') {
                                        aceEdit.getSession()
                                            .replace(range(row, 0, row, line.length), "\t${line.trimEnd()} ")
                                        // TODO regexp
                                    } else if (firstChar.isDigit() && trimmed.length > 1) {
                                        if (trimmed[1] == '.' || (trimmed.length > 2 && trimmed[1].isDigit() && trimmed[2] == '.')) {
                                            aceEdit.getSession()
                                                .replace(range(row, 0, row, line.length), "\t${line.trimEnd()} ")
                                        }
                                    }
                                }
                            }
                            // enter was pressed; insert our custom list bullets if applicable
                        } else if (delta.lines.size == 2 && (delta.lines[1].isEmpty())) {
                            val prevLine = aceEdit.getSession().getLine(delta.start.row)
                            val trimmed = prevLine.trim()
                            val char = when {
                                trimmed.startsWith('.') -> '.'
                                trimmed.startsWith('+') -> '+'
                                else -> null
                            }

                            val rowToUpdate = delta.end.row
                            if (char != null) {
                                // ensure they had something on prev line, otherwise end list
                                if (trimmed.length > 1) {
                                    aceEdit.getSession().replace(range(rowToUpdate, 0, rowToUpdate, 0), "$char ")
                                    val newLine = aceEdit.getSession().getLine(rowToUpdate)
                                    aceEdit.moveCursorTo(rowToUpdate, newLine.length)
                                } else {
                                    // TODO doesn't work
                                    aceEdit.getSession().indentRows(rowToUpdate, rowToUpdate, "")
                                    aceEdit.moveCursorTo(rowToUpdate, 0)
                                    aceEdit.getSession().replace(range(rowToUpdate, 0, rowToUpdate, 0), "")
                                }
                            }
                        }
                    }
                    val text = MarkdownService.toMd(cards, false).let {
                        if (it.isNotEmpty()) {
                            it + "\n"
                        } else {
                            it
                        }
                    }
                    aceEdit.setValue(text)
                    aceEdit.focus()

                    aceEdit.clearSelection()

                    aceEdit.getSession().apply {
                        setMode("ace/mode/markdown")
                        setUseWrapMode(true)
                        setTabSize(ACE_TAB_SIZE)
                        setUseSoftTabs(true)
                    }
                    aceEdit.keyBinding.addKeyboardHandler(::keyHandler)

                    addCommand(aceEdit, "prev-card", "Ctrl-Home", "Command-Home") {
                        moveNextOrPrev(false)
                        true
                    }
                    addCommand(aceEdit, "next-card", "Ctrl-End", "Command-End") {
                        moveNextOrPrev(true)
                        true
                    }
                    addCommand(aceEdit, "top", "Alt-Home", "Option-Home") {
                        it.moveCursorTo(0, 0)
                        true
                    }
                    addCommand(aceEdit, "bottom", "Alt-End", "Option-End") {
                        it.moveCursorTo(it.getValue().split("\n").size - 1, 0)
                        true
                    }
                    addCommand(aceEdit, "next-column", "Alt-Right", "Option-Right") {
//                        eventBus.fire(NavEditor(RIGHT, now()))
                        true
                    }
                    addCommand(aceEdit, "prev-column", "Alt-Left", "Option-Left") {
//                        eventBus.fire(NavEditor(LEFT, now()))
                        true
                    }
                    addCommand(aceEdit, "autoCloseCodeBlock", "Enter", "Enter") { editor ->

                        val cursorPosition = editor.getCursorPosition()
                        val currentLine = editor.getSession().getLine(cursorPosition.row)

                        when (currentLine.trim()) {
                            "```", "---" -> {
                                val delimiter = currentLine.trim()
                                editor.insert("\n\n$delimiter")
                                editor.moveCursorTo(cursorPosition.row + 1, 0)
                                true // Prevents the default Enter behavior
                            }

                            else -> false // Allows the default Enter behavior
                        }
                    }

                    if (cursorPosition != null) {
                        aceEdit.moveCursorTo(cursorPosition.row, cursorPosition.column)
                    }

//                    el(this)!!.style.height = "50%";
                    aceEdit.resize()
                }
            }
        }
    }

    fun addCommand(editor: AceWrapper.AceEditor, name: String, win: String, mac: String,
                   exec: (AceWrapper.AceEditor) -> Boolean) {
        editor.commands.addCommand(object: AceWrapper.AceCommand {
            override val name = name
            override val bindKey = object: AceWrapper.AceCommandKey {
                override val win = win
                override val mac = mac
            }
            override val exec = exec
        })
    }

    val noCommand = object: AceWrapper.CommandObject {
        override val command = null
    }

    private fun keyHandler(data: String, hash: String, keyString: String, keyCode: Int, event: KeyboardEvent?): AceWrapper.CommandObject? {
        if (event == null) {
            return noCommand
        }
        // TODO move these to commands
        // these have to be overridden from CanvasView -- it'd be better if ace could send these up
        if (event.ctrlKey && event.altKey && (event.keyCode == 38 || event.keyCode == 40)) { // ctrl up/down
            save()
            if (event.shiftKey) {
//                eventBus.fire(AddColumn(canvasName, Split.HORIZONTAL, columnId))
            } else {
//                eventBus.fire(SplitColumn(canvasName, Split.HORIZONTAL, columnId))
            }
        } else if (event.ctrlKey && event.altKey && (event.keyCode == 37 || event.keyCode == 39)) { // ctrl left/right
            save()
            if (event.shiftKey) {
//                eventBus.fire(AddColumn(canvasName, Split.VERTICAL, columnId))
            } else {
//                eventBus.fire(SplitColumn(canvasName, Split.VERTICAL, columnId))
            }
        }
        // this maybe is wrong/ineffective
        return noCommand
    }

    private fun moveNextOrPrev(next: Boolean) {
        val split = aceEditor!!.getValue().split("\n")
        val current = aceEditor!!.getCursorPosition().row.let {
            if (it == 0 && !next) {
                split.size
            } else {
                it
            }
        }
        val iterateTo = current + (1 * (if (next) 1 else -1))
        var found = 0

        var empty = 0
        val range = if (next) {
            iterateTo until split.size
        } else {
            0..iterateTo
        }
        for (i in range) {
            val trimmed = split[i].trim()
            if (trimmed.isEmpty()) {
                empty++
            } else {
                empty = 0
            }
            if (trimmed.startsWith("#")) {
                found = i
                if (next) {
                    break
                }
            }
        }
        this.aceEditor!!.moveCursorTo(found, 0)
    }

    fun save() {
        val value = aceEditor!!.getValue()
//        eventBus.fire(ColumnEdited(value, canvasName, columnId))
    }
}