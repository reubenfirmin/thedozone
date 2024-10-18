package tdz.model.markdown

import tdz.model.card.CardCodeBlock
import tdz.model.card.LinkFactory
import tdz.model.card.RichText
import tdz.model.card.Text

class MarkdownLine(line: String) {

    private val lineElements: List<RichText>

    init {
        val found = mutableListOf<RichText>()

        val currentText = StringBuilder()
        val description = StringBuilder()
        val codeBlock = StringBuilder()
        var buildingLink = false
        var buildingLinkDescription = false
        var buildingCode = false

        fun storeCurrent() {
            if (currentText.isNotEmpty()) {
                found.add(Text(currentText.toString()))
                currentText.clear()
            }
        }

        line.forEach { character ->
            if (character == '[') {
                storeCurrent()
                buildingLink = true
                currentText.append(character)
            } else if (buildingLink && character == ']') {
                // remove space between link and description
                found.add(LinkFactory(currentText.substring(1).trim(), description.toString()).resolve())
                currentText.clear()
                description.clear()
                buildingLink = false
            } else if (buildingLink && character == '(') {
                buildingLinkDescription = true
            } else if (buildingLinkDescription && character == ')') {
                buildingLinkDescription = false
            } else if (buildingLinkDescription) {
                description.append(character)
            } else if (character == '`') {
                buildingCode = if (buildingCode) {
                    found.add(CardCodeBlock(codeBlock.toString()))
                    codeBlock.clear()
                    false
                } else {
                    storeCurrent()
                    true
                }
            } else if (buildingCode) {
                codeBlock.append(character)
            } else {
                currentText.append(character)
            }
        }
        storeCurrent()
        lineElements = found.toList()
    }

    fun elements() = lineElements
}