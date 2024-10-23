package tdz.model.markdown

import tdz.model.Card
import tdz.model.CardMetadata
import tdz.model.card.*

data class ParseState(val column: Int,
                      val cards: MutableList<Card> = mutableListOf(),
                      private val segmentsBuffer: MutableList<CardSegment> = mutableListOf(),
                      var prevNewline: Boolean = false,
) {

    private var inCodeBlock = false
    private val codeBuffer = mutableListOf<String>()
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

    fun newColumn(column: Int) {
        currentColumn = column
        index = 0
    }

    fun getCurrentColumn() = currentColumn

    fun createCardFromSegments() {
        if (segmentsBuffer.size > 0) {
            // lists have no terminator
            if (inListBlock) {
                finishList()
            }

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

        // TODO worth testing further what combination of these is reasonable. maybe segments + code || segments + diagram. seems
        // impossible to have code + diagram
        if (segmentsBuffer.size > 0) {
            cards.add(Card(segmentsBuffer.toList(), CardMetadata(currentColumn), index))
        }

        // unfinished code is just text
        if (codeBuffer.size > 0) {
            cards.add(Card(codeBuffer.map { CardText(MarkdownLine(it).elements()) }, CardMetadata(currentColumn), index++))
        }

        // unfinished diagram is just text
        if (diagramBuffer.size > 0) {
            cards.add(Card(diagramBuffer.map { CardText(MarkdownLine(it).elements()) }, CardMetadata(currentColumn), index++))
        }

        // unfinished list is actually list
        if (listBuffer.size > 0) {
            finishList()
        }

        return cards.toList()
    }
}