package tdz.model.markdown

import tdz.model.ColumnId

import tdz.model.markdown.MarkdownReader.metadataLine

object MarkdownCardMetadata {

    fun consumeMetadataLine(trimmed: String, state: ParseState): Boolean {
        // right now this is column/width. only column is used...width could be repurposed
        val proposedMetadata = metadataMarker(trimmed)
        return if (proposedMetadata != null) {
            if (ColumnId(proposedMetadata.first) != state.getCurrentColumn()) {
                state.newColumn(ColumnId(proposedMetadata.first))
            }
            true
        } else {
            false
        }
    }

    // The width group (second) is vestigial; kept because stored files carry it (see MarkdownWriter).
    private fun metadataMarker(line: String): Pair<Int, Int>? {
        val metadataMatch = metadataLine.matchEntire(line)
        return if (metadataMatch != null) {
            metadataMatch.groupValues[1].toInt() to metadataMatch.groupValues[2].toInt()
        } else {
            null
        }
    }
}