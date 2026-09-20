package tdz.model.markdown

import tdz.EDITOR_TAB_SIZE
import tdz.model.card.*
import tdz.model.card.ListMemberType.*

/** The leading marker that flags a list item as done — written by [MarkdownWriter], read here. */
internal const val DONE_MARKER = "/"

internal object MarkdownListElement {

    private val numbered = "[\\t ]*[0-9]+\\.(.*)".toRegex()
    private val bulleted = "[\\t ]*([*|+])+(.*)".toRegex()
    private val arrowed = "[\\t ]*(-+)+(.*)".toRegex()

    /** Bulleted / numbered lines become interactive [CardListItem]s grouped into a list. */
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

    /** Dash-prefixed lines become non-interactive [Callout] segments (terminating any open list). */
    fun consumeCallout(line: String, state: ParseState): Boolean {
        val callout = calloutElement(line) ?: return false
        state.appendGenericSegment(callout)
        return true
    }

    private fun listElement(line: String): CardListItem? {
        val level = levelOf(line)

        var type: ListMemberType? = null
        var text: String? = null
        var weight = 0

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
            }
        }

        if (type == null || text == null) return null

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        var done = false
        val cleaned = if (trimmed.startsWith(DONE_MARKER)) {
            done = true
            trimmed.substring(DONE_MARKER.length)
        } else {
            trimmed
        }
        return CardListItem(MarkdownLine(cleaned).elements(), level, type, weight, done)
    }

    private fun calloutElement(line: String): Callout? {
        val arrow = arrowed.matchEntire(line) ?: return null
        val weight = arrow.groupValues[1].length
        val trimmed = arrow.groupValues[2].trim()
        if (trimmed.isEmpty()) return null
        // Callouts have no done state; strip a stray leading done-marker for parity with list items.
        val cleaned = if (trimmed.startsWith(DONE_MARKER)) trimmed.substring(DONE_MARKER.length) else trimmed
        return Callout(MarkdownLine(cleaned).elements(), levelOf(line), weight)
    }

    private fun levelOf(line: String): Int {
        val levelTabs = numTabsAtStart(line)
        val levelSpaces = numSpacesAtStart(line)
        return when {
            levelTabs > 0 -> levelTabs
            levelSpaces > 0 -> levelSpaces / EDITOR_TAB_SIZE
            else -> 0
        }
    }

    private fun numTabsAtStart(line: String): Int {
        return line.takeWhile { it == '\t' }.count()
    }

    private fun numSpacesAtStart(line: String): Int {
        return line.takeWhile { it == ' ' }.count()
    }
}
