package tdz.model.markdown

import tdz.model.Card
import tdz.model.ColumnId
import tdz.model.CardMetadata
import tdz.model.card.*

// A mutable state machine, not a value: it carries `var` flags and buffers outside the constructor,
// so the generated equals/hashCode/copy of a data class would be meaningless (and copy() a footgun
// that shares the live buffers). Plain class.
class ParseState(val column: ColumnId,
                 val cards: MutableList<Card> = mutableListOf(),
                 private val segmentsBuffer: MutableList<CardSegment> = mutableListOf(),
) {

    private var inCodeBlock = false
    private val codeBuffer = mutableListOf<String>()
    private var inBlockquote = false
    private val blockquoteBuffer = mutableListOf<String>()
    private var inDiagramBlock = false
    private val diagramBuffer = mutableListOf<String>()
    private var inListBlock = false
    private val listBuffer = mutableListOf<CardListItem>()
    private var index = 0
    private var currentColumn = column

    fun inCodeBlock() = inCodeBlock

    fun startCode() {
        inCodeBlock = true
        // lists have no terminator
        if (inListBlock) {
            finishList()
        }
    }

    fun appendCode(line: String) {
        codeBuffer.add(line)
    }

    fun finishCode() {
        segmentsBuffer.add(CardCodeBlock(codeBuffer.joinToString("\n")))
        codeBuffer.clear()
        inCodeBlock = false
    }

    fun inBlockquote() = inBlockquote

    fun startBlockquote() {
        inBlockquote = true
        // lists have no terminator
        if (inListBlock) {
            finishList()
        }
    }

    fun appendBlockquote(line: String) {
        blockquoteBuffer.add(line)
    }

    fun finishBlockquote() {
        segmentsBuffer.add(Blockquote(blockquoteBuffer.joinToString("\n")))
        blockquoteBuffer.clear()
        inBlockquote = false
    }

    fun inDiagram() = inDiagramBlock

    fun startDiagram() {
        inDiagramBlock = true
        // lists have no terminator
        if (inListBlock) {
            finishList()
        }
    }

    fun appendToDiagram(line: String) {
        diagramBuffer.add(line)
    }

    fun finishDiagram() {
        segmentsBuffer.add(CardDiagram(diagramBuffer.joinToString("\n")))
        diagramBuffer.clear()
        inDiagramBlock = false
    }

    fun inList() = inListBlock

    fun startList() {
        inListBlock = true
    }

    fun appendToList(listItem: CardListItem) {
        listBuffer.add(listItem)
    }

    fun finishList() {
        segmentsBuffer.add(listBuffer.toGroup())
        listBuffer.clear()
        inListBlock = false
    }

    fun newColumn(column: ColumnId) {
        currentColumn = column
        index = 0
    }

    fun getCurrentColumn() = currentColumn

    /**
     * Close any open terminator-less blocks into [segmentsBuffer], blockquote BEFORE list. Both a
     * card boundary and end-of-input must do this identically before emitting a card: a blockquote
     * ends at the line that breaks it, and a list has no terminator, so each must be flushed FIRST
     * or a block-only card (whose [segmentsBuffer] is otherwise empty) would be dropped.
     */
    private fun flushPendingBlocks() {
        if (inBlockquote || blockquoteBuffer.size > 0) {
            finishBlockquote()
        }
        if (inListBlock || listBuffer.size > 0) {
            finishList()
        }
    }

    fun createCardFromSegments() {
        flushPendingBlocks()

        if (segmentsBuffer.size > 0) {
            val newCard = Card(segmentsBuffer.toList(), CardMetadata(currentColumn), index++)
            cards.add(newCard)
            segmentsBuffer.clear()
        }
    }

    fun appendGenericSegment(segment: CardSegment) {
        // lists have no terminator
        if (inListBlock) {
            finishList()
        }

        segmentsBuffer.add(segment)
    }

    fun finalize(): List<Card> {

        // Trailing blocks at end-of-input have no breaking line; flush them so they're included below
        // (previously a trailing list was finished AFTER the card was created and lost).
        flushPendingBlocks()

        if (segmentsBuffer.size > 0) {
            cards.add(Card(segmentsBuffer.toList(), CardMetadata(currentColumn), index++))
        }

        // unfinished code is just text
        if (codeBuffer.size > 0) {
            cards.add(Card(codeBuffer.map { CardText(MarkdownLine(it).elements()) }, CardMetadata(currentColumn), index++))
        }

        // unfinished diagram is just text
        if (diagramBuffer.size > 0) {
            cards.add(Card(diagramBuffer.map { CardText(MarkdownLine(it).elements()) }, CardMetadata(currentColumn), index++))
        }

        return cards.toList()
    }
}