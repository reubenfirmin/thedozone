package tdz.model.markdown

import tdz.ACE_TAB_SIZE
import tdz.model.card.*
import tdz.model.card.ListMemberType.*

internal object MarkdownListElement {

    private val numbered = "[\\t ]*[0-9]*\\.(.*)".toRegex()
    private val bulleted = "[\\t ]*([*|+])+(.*)".toRegex()
    private val arrowed = "[\\t ]*(-+)+(.*)".toRegex()

    fun consumeListElement(line: String, state: ParseState): Boolean {
        val step = listElement(line)
        return if (step != null) {
            if (!state.inList()) {
                state.startList()
            }
            state.appendToList(step)
            true
        } else {
            false
        }
    }

    private fun listElement(line: String): CardListItem? {
        val levelTabs = numTabsAtStart(line)
        val levelSpaces = numSpacesAtStart(line)
        val level = when {
            levelTabs > 0 -> {
                levelTabs
            }
            levelSpaces > 0 -> {
                levelSpaces / ACE_TAB_SIZE
            }
            else -> {
                0
            }
        }

        var type: ListMemberType? = null
        var text: String? = null
        var weight: Int = 0

        val number = numbered.matchEntire(line)

        if (number != null) {
            type = NUMBERED
            text = number.groupValues[1]
        } else {
            val bullet = bulleted.matchEntire(line)

            if (bullet != null) {
                type = BULLET
                text = bullet.groupValues[2]
                weight = bullet.groupValues[1].length
            } else {
                val arrow = arrowed.matchEntire(line)
                if (arrow != null) {
                    type = ARROW
                    text = arrow.groupValues[2]
                    weight = arrow.groupValues[1].length
                }
            }
        }

        if (type != null) {
            val trimmed = text!!.trim()
            return if (trimmed.isNotEmpty()) {
                var done = false
                val cleaned = if (trimmed.startsWith("/")) {
                    done = true
                    trimmed.substring(1)
                } else {
                    trimmed
                }
                CardListItem(MarkdownLine(cleaned).elements(), level, type, weight, done)
            } else {
                null
            }
        }
        return null
    }

    private fun numTabsAtStart(line: String): Int {
        return line.takeWhile { it == '\t' }.count()
    }

    private fun numSpacesAtStart(line: String): Int {
        return line.takeWhile { it == ' ' }.count()
    }
}