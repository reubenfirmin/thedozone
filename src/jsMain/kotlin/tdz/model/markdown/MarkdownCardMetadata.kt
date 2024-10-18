package tdz.model.markdown

import tdz.model.markdown.MarkdownReader.metadataLine

object MarkdownCardMetadata {

    fun consumeMetadataLine(trimmed: String, state: ParseState): Boolean {
        // right now this is column/width. only column is used...width could be repurposed
        val proposedMetadata = metadataMarker(trimmed)
        return if (proposedMetadata != null) {
            if (proposedMetadata.first != state.getCurrentColumn()) {
                state.newColumn(proposedMetadata.first)
            }
            true
        } else {
            false
        }
    }

    // TODO remove width
    private fun metadataMarker(line: String): Pair<Int, Int>? {
        val metadataMatch = metadataLine.matchEntire(line)
        return if (metadataMatch != null) {
            metadataMatch.groupValues[1].toInt() to metadataMatch.groupValues[2].toInt()
        } else {
            null
        }
    }
}