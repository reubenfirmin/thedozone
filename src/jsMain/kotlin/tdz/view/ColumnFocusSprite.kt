package tdz.view

import web.dom.ElementId
import web.dom.document
import web.html.HTMLDivElement
import web.html.HTMLElement
import zoned.framework.interop.getHtmlElement

/**
 * A single shared decorative sprite (the Nyan cat, in Rainbow Gaps) pinned to the focused/hovered
 * column's top-right corner — a bold focus indicator for themes whose border highlight is too faint.
 *
 * It lives as one `position: fixed` element on the body rather than inside any column: the columns
 * are internal scroll containers, so a child would scroll out of view, and exactly one column is
 * focused at a time, so one overlay suffices. [update] is driven entirely from
 * `ColumnContainer.setFocused`, which is the single place column focus changes.
 */
object ColumnFocusSprite {

    private const val ID = "tdz-focus-sprite"
    private const val WIDTH = 64.0     // px; sprite is ~1.87:1, so height follows
    private const val HEIGHT = 34.0
    private const val PAD = 8.0        // inset from the column's top-right corner

    /**
     * Show the [spriteUri] over [columnElementId]'s top-right corner when [focused], else hide it.
     * An empty [spriteUri] (any theme but Rainbow Gaps) always hides it.
     */
    fun update(columnElementId: String, spriteUri: String, focused: Boolean) {
        val sprite = ensureElement()
        val column = getHtmlElement(columnElementId)
        if (spriteUri.isEmpty() || !focused || column == null) {
            sprite.style.display = "none"
            return
        }
        val rect = column.getBoundingClientRect()
        sprite.style.backgroundImage = "url(\"$spriteUri\")"
        sprite.style.left = "${rect.right - WIDTH - PAD}px"
        sprite.style.top = "${rect.top + PAD}px"
        sprite.style.display = "block"
    }

    private fun ensureElement(): HTMLElement {
        (getHtmlElement(ID))?.let { return it }
        val el = document.createElement("div") as HTMLDivElement
        el.id = ElementId(ID)
        el.style.apply {
            position = "fixed"
            width = "${WIDTH}px"
            height = "${HEIGHT}px"
            backgroundRepeat = "no-repeat"
            backgroundSize = "contain"
            backgroundPosition = "center"
            pointerEvents = "none"   // purely decorative — never intercept clicks
            zIndex = "60"
            display = "none"
        }
        document.body?.appendChild(el)
        return el
    }
}
