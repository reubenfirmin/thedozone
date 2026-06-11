package tdz.model.card

/**
 * A list item, either bulleted, arrowed or numeric
 * @param level 0 indexed
 */
data class CardListItem(override val text: List<RichText>,
                        val level: Int,
                        val type: ListMemberType,
                        val weight: Int,
                        val done: Boolean): RichTextSegment, ListMember

enum class ListMemberType {
    BULLET,
    NUMBERED,
    ARROW
}

data class ListGroup(
    val members: List<ListMember>,
    val type: ListMemberType,
    val level: Int): ListMember, CardSegment

interface ListMember {}

/**
 * Remove completed ([CardListItem.done]) items from this group, recursing into nested groups.
 * Returns null when no members remain, so callers can drop the now-empty group entirely.
 */
fun ListGroup.withoutDoneItems(): ListGroup? {
    val remaining = members.mapNotNull { member ->
        when (member) {
            is CardListItem -> member.takeUnless { it.done }
            is ListGroup -> member.withoutDoneItems()
            else -> member
        }
    }
    return if (remaining.isEmpty()) null else copy(members = remaining)
}

fun List<CardListItem>.toGroup(): ListGroup {
    if (isEmpty()) {
        return ListGroup(emptyList(), ListMemberType.BULLET, 0)
    }

    val baseLevel = first().level
    val baseType = first().type

    // Group items into clusters when level or type changes
    val clusters = buildList {
        var currentCluster = mutableListOf<CardListItem>()

        for (item in this@toGroup) {

            // Start new cluster when:
            // 1. Level changes OR
            // 2. At same level but type changes
            if (currentCluster.isNotEmpty() &&
                (item.level != currentCluster.first().level || item.type != baseType))
            {
                add(currentCluster.toList())
                currentCluster = mutableListOf()
            }

            currentCluster.add(item)
        }
        if (currentCluster.isNotEmpty()) {
            add(currentCluster.toList())
        }
    }

    val members = clusters.map { cluster ->
        when {
            // Base level items become direct members
            cluster.first().level == baseLevel -> cluster
            // Deeper level items form a recursive subgroup
            cluster.first().level > baseLevel -> listOf(cluster.toGroup())
            else -> error("Unexpected item level ${cluster.first().level} below base level $baseLevel")
        }
    }.flatten()

        /*
    if (baseType == ListMemberType.NUMBERED) {
        val mergedMembers = members.filterIsInstance<CardListItem>()
        val subgroups = members.filterIsInstance<ListGroup>()
        return ListGroup(mergedMembers + subgroups, baseType, baseLevel)
    }
    */

    return ListGroup(members, baseType, baseLevel)
}