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
        val codeBlock = StringBuilder()
        var buildingLink = false
        var buildingCode = false

        fun storeCurrent() {
            if (currentText.isNotEmpty()) {
                found.add(Text(currentText.toString()))
                currentText.clear()
            }
        }

        fun storeLink(body: String) {
            // The label is the LAST "(...)" group, so a link whose target itself contains parens —
            // "[map(40.5, -73.9) (My Location)]" — keeps its target intact. (Splitting on the FIRST
            // "(" used to mangle labeled map links into unparseable canvas links.)
            val split = Regex("""^(.*?)\s*\(([^()]*)\)$""").matchEntire(body.trim())
            if (split != null) {
                found.add(LinkFactory(split.groupValues[1].trim(), split.groupValues[2]).resolve())
            } else {
                found.add(LinkFactory(body.trim(), "").resolve())
            }
        }

        line.forEach { character ->
            if (character == '[') {
                storeCurrent()
                buildingLink = true
                currentText.append(character)
            } else if (buildingLink && character == ']') {
                storeLink(currentText.substring(1))
                currentText.clear()
                buildingLink = false
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