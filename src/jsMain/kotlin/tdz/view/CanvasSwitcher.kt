package tdz.view

import tdz.model.CanvasName
import tdz.model.Model

/**
 * Returns the direct children of [current] — boards whose parentName equals [current] — sorted
 * alphabetically by leaf. Uses the model as the authoritative source, so link-noise (e.g. the
 * auto-inserted `[..]` parent-link card) never pollutes the list. Drives the board palette's
 * child list ([Omnibox]).
 */
fun childBoardNames(model: Model, current: CanvasName): List<CanvasName> =
    model.allCanvasNames()
        .filter { !it.isRoot() && it.parentName().normalized() == current.normalized() }
        .sortedBy { it.leaf().lowercase() }
