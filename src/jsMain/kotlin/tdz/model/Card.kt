package tdz.model

import tdz.model.card.*

data class CardMetadata(val columnId: ColumnId)

/**
 * A card's positional identity: its column and its index within it (its position at last render).
 * Cards have no stable ids by design — the markdown file is the source of truth and cards are
 * projections — so a ref is only valid until the column is next rewritten.
 */
data class CardRef(val columnId: ColumnId, val index: Int)

val Card.ref: CardRef get() = CardRef(metadata.columnId, index)

data class Card(val segments: List<CardSegment>, val metadata: CardMetadata, val index: Int) {

    /**
     * A copy with every completed checklist item removed and now-empty list groups pruned.
     * Structurally equal to the original when nothing is done, so callers can detect a no-op
     * via equality. Done items live inside [ListGroup]s, so the removal recurses into them.
     */
    fun withoutDoneItems(): Card =
        copy(segments = segments.mapNotNull { segment ->
            when (segment) {
                is CardListItem -> segment.takeUnless { it.done }   // defensive: a bare list item
                is ListGroup -> segment.withoutDoneItems()
                else -> segment
            }
        })

    fun headingOnly() = segments.filterNot { it is CardHeading }.isEmpty()

    /** A "title" card: its only content is a single level-1 heading (e.g. `# Tracking`). */
    fun isH1Only() = segments.size == 1 && (segments[0] as? CardHeading)?.level == 1

    fun infoOnly() = segments.filterNot { it is CardText || it is CardHeading || it is Callout }.isEmpty()

    fun isCanvasLink() = link()?.isCanvasLink() ?: false

    fun isImageLink() = link()?.isImage() ?: false

    private fun steps() = segments.filterIsInstance<CardListItem>()

    fun done() = steps().let { it.isNotEmpty() && it.all { task -> task.done } }

    fun tagId() = "card_${metadata.columnId}_$index"

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
         * The cards visually "under" the h1-only card at [h1Index] in this column-ordered list:
         * the contiguous run after it up to (but not including) the next h1-only card or the end
         * of the column. The h1 itself is not included. Empty if [h1Index] is out of range or the
         * card there is not h1-only. Used to drag a section header together with its cards.
         */
        fun List<Card>.cardsUnderH1(h1Index: Int): List<Card> {
            if (h1Index !in indices || !this[h1Index].isH1Only()) return emptyList()
            return drop(h1Index + 1).takeWhile { !it.isH1Only() }
        }

        /**
         * The card's checkable items in RENDER order — list groups in segment order, nested groups
         * depth-first — matching CardRenderer's traversal exactly. This is the positional contract
         * the delegated task-click handler relies on: the clicked row's ordinal among the card's
         * task-row elements indexes into this list.
         */
        fun Card.taskItems(): List<CardListItem> {
            fun collect(group: ListGroup): List<CardListItem> = group.members.flatMap { member ->
                when (member) {
                    is CardListItem -> listOf(member)
                    is ListGroup -> collect(member)
                    else -> emptyList()
                }
            }
            return segments.filterIsInstance<ListGroup>().flatMap { collect(it) }
        }
    }
}
