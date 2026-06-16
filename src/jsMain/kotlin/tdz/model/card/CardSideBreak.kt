package tdz.model.card

/**
 * A marker segment for a `//` line: it divides a single card into FACES ("sides") without splitting
 * it into separate cards (unlike a blank line / `==`). N-1 breaks ⇒ N sides; the renderer shows one
 * side at a time with a flip affordance. Carries no content — it's purely a boundary.
 */
object CardSideBreak : CardSegment

/**
 * Partition segments into sides on each [CardSideBreak] (the breaks themselves are dropped). No
 * breaks ⇒ a single side holding everything; N breaks ⇒ N+1 sides (some possibly empty). Pure.
 */
fun List<CardSegment>.splitOnSideBreak(): List<List<CardSegment>> {
    val sides = mutableListOf<MutableList<CardSegment>>(mutableListOf())
    for (segment in this) {
        if (segment is CardSideBreak) sides.add(mutableListOf()) else sides.last().add(segment)
    }
    return sides
}
