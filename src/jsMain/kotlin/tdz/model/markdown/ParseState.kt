package tdz.model.markdown

import tdz.model.Card
import tdz.model.ColumnId
import tdz.model.CardMetadata
import tdz.model.card.*

data class ParseState(val column: ColumnId,
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

    fun createCardFromSegments() {
        // A blockquote ends at the line that breaks it (often a blank line / card boundary);
        // flush any pending quote into the card before closing it.
        if (inBlockquote) {
            finishBlockquote()
        }
        // Lists have no terminator: flush any pending list into the segments FIRST, so a
        // list-only card (whose segmentsBuffer is otherwise empty) isn't dropped.
        if (inListBlock) {
            finishList()
        }

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

        // A trailing blockquote at end-of-input has no breaking line; flush it.
        if (inBlockquote || blockquoteBuffer.size > 0) {
            finishBlockquote()
        }

        // A trailing list has no terminator; flush it into the current card's segments FIRST
        // so it's included below (previously it was finished AFTER the card was created and lost).
        if (inListBlock || listBuffer.size > 0) {
            finishList()
        }

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