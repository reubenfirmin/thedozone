package tdz.model

import tdz.model.card.*

data class CardMetadata(val columnId: Int)

data class Card(val segments: List<CardSegment>, val metadata: CardMetadata, val index: Int) {

    fun headingOnly() = segments.filterNot { it is CardHeading }.isEmpty()

    fun infoOnly() = segments.filterNot { it is CardText || it is CardHeading }.isEmpty()

    fun isCanvasLink() = link()?.isCanvasLink() ?: false

    fun isImageLink() = link()?.isImage() ?: false

    private fun steps() = segments.filterIsInstance<CardListItem>()

    fun done() = steps().let { it.isNotEmpty() && it.all { task -> task.done } }

    fun tagId() = "card_${metadata.columnId}_$index"

    fun linkedCanvas() = link()?.let { if (it is CanvasLink) it.canvasName else null }

    /**
     * Only applies if the only thing is a link
     */
    private fun link(): LinkText? {
        return if (segments.size == 1 && segments[0].isLink()) {
            val links = segments[0].links()
            if (links.size == 1) {
                links[0]
            } else {
                null
            }
        } else {
            null
        }
    }

    companion object {
        /**
         * @return column to card index in column
         */
        fun tagIdToCardIndex(tag: String): Pair<Int, Int> {
            val tokens = tag.split("_")
            return tokens[1].toInt() to tokens[2].toInt()
        }
    }
}
