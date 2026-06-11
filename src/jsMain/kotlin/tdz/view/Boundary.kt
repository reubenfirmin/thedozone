package tdz.view

import tdz.model.Split
import kotlin.math.roundToInt

data class Boundary(val leftX: Int, val topY: Int, val width: Int, val height: Int) {

    fun surrounds(x: Int, y: Int): Boolean {
        return x >= leftX && x <= (leftX + width) && y >= topY && y <= (topY + height)
    }

    /**
     * if x/y are outside of this boundary, wrap around to the opposite side. produces pixel inside corner to avoid
     * shared boundary issues
     */
    fun wrap(x: Int, y: Int): Pair<Int, Int> {
        val newX = if (x < leftX) {
            leftX + width
        } else if (x > leftX + width) {
            leftX
        } else {
            x
        }

        val newY = if (y < topY) {
            topY + height
        } else if (y > topY + height) {
            topY
        } else {
            y
        }

        val out = inset(newX, newY)

        return out
    }

    // nudge inside the boundary by 1 pixel if on the edge
    fun inset(x: Int, y: Int): Pair<Int, Int> {
        val newX = when (x) {
            leftX -> {
                leftX + 1
            }
            leftX + width -> {
                leftX + width - 1
            }
            else -> {
                x
            }
        }

        val newY = when (y) {
            topY -> {
                topY + 1
            }
            topY + height -> {
                topY + height - 1
            }
            else -> {
                y
            }
        }
        return newX to newY
    }

    companion object {

        /**
         * Calculate the boundary of this particular sibling in the given direction within the parent
         */
        fun calculateBoundary(split: Split, parentBoundary: Boundary, siblingIdx: Int, numSiblings: Int): Boundary {
            return when (split) {
                Split.HORIZONTAL -> {
                    val width = parentBoundary.width
                    val height = (parentBoundary.height.toDouble() / numSiblings).roundToInt()
                    val leftX = parentBoundary.leftX
                    val topY = (parentBoundary.topY + (height * siblingIdx))
                    Boundary(leftX = leftX, topY = topY, width = width, height = height)
                }
                Split.VERTICAL -> {
                    val width = (parentBoundary.width.toDouble() / numSiblings).roundToInt()
                    val height = parentBoundary.height
                    val leftX = (parentBoundary.leftX + (width * siblingIdx))
                    val topY = parentBoundary.topY
                    Boundary(leftX = leftX, topY = topY, width = width, height = height)
                }
            }
        }
    }
}