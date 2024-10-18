package tdz.model.markdown

import tdz.model.Card
import tdz.model.CardMetadata
import tdz.model.card.CardCodeBlock
import tdz.model.card.CardDiagram
import tdz.model.card.CardSegment
import tdz.model.card.CardText

data class ParseState(val column: Int,
                      val cards: MutableList<Card> = mutableListOf<Card>(),
                      val segmentsBuffer: MutableList<CardSegment> = mutableListOf<CardSegment>(),
                      var prevNewline: Boolean = false,
) {

    private var inCodeBlock = false
    private val codeBuffer = mutableListOf<String>()
    private var inDiagramBlock = false
    private val diagramBuffer = mutableListOf<String>()
    private var index = 0
    private var currentColumn = column

    fun startCode() {
        inCodeBlock = true
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
    }

    fun appendToDiagram(line: String) {
        diagramBuffer.add(line)
    }

    fun finishDiagram() {
        segmentsBuffer.add(CardDiagram(diagramBuffer.joinToString("\n")))
        diagramBuffer.clear()
        inDiagramBlock = false
    }

    fun inCodeBlock() = inCodeBlock

    fun newColumn(column: Int) {
        currentColumn = column
        index = 0
    }

    fun getCurrentColumn() = currentColumn

    fun createCardFromSegments() {
        if (segmentsBuffer.size > 0) {
            val newCard = Card(segmentsBuffer.toList(), CardMetadata(currentColumn), index++)
            cards.add(newCard)
            segmentsBuffer.clear()
        }
    }

    fun finalize(): List<Card> {

        if (segmentsBuffer.size > 0) {
            cards.add(Card(segmentsBuffer.toList(), CardMetadata(currentColumn), index))
        }

        if (codeBuffer.size > 0) {
            cards.add(Card(codeBuffer.map { CardText(MarkdownLine(it).elements()) }, CardMetadata(currentColumn), index++))
        }

        if (diagramBuffer.size > 0) {
            cards.add(Card(diagramBuffer.map { CardText(MarkdownLine(it).elements()) }, CardMetadata(currentColumn), index++))
        }

        return cards.toList()
    }
}