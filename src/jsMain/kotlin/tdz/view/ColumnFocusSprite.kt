package tdz.view

import kotlinx.css.*
import kotlinx.html.div
import web.html.HTMLElement
import zoned.framework.interop.addToBody
import zoned.framework.interop.css
import zoned.framework.interop.getHtmlElement
import zoned.framework.routing.Zone.Companion.zone

/**
 * A single shared decorative sprite (the Nyan cat, in Rainbow Gaps) pinned to the focused/hovered
 * column's top-right corner — a bold focus indicator for themes whose border highlight is too faint.
 *
 * It lives as one `position: fixed` body-level zone ([Zones.FOCUS_SPRITE]) rather than inside any
 * column: the columns are internal scroll containers, so a child would scroll out of view, and
 * exactly one column is focused at a time, so one overlay suffices. [update] is driven entirely
 * from `ColumnContainer.setFocused`, which is the single place column focus changes.
 */
object ColumnFocusSprite {

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
        // Geometry is dynamic (per focus change), so it goes through the element.style sink.
        val rect = column.getBoundingClientRect()
        sprite.style.backgroundImage = "url(\"$spriteUri\")"
        sprite.style.left = "${rect.right - WIDTH - PAD}px"
        sprite.style.top = "${rect.top + PAD}px"
        sprite.style.display = "block"
    }

    private fun ensureElement(): HTMLElement {
        Zones.FOCUS_SPRITE.resolve()?.let { return it }
        addToBody {
            div {
                zone(Zones.FOCUS_SPRITE)
                css {
                    position = Position.fixed
                    width = WIDTH.px
                    height = HEIGHT.px
                    backgroundRepeat = BackgroundRepeat.noRepeat
                    backgroundSize = "contain"
                    backgroundPosition = RelativePosition.center
                    pointerEvents = PointerEvents.none   // purely decorative — never intercept clicks
                    zIndex = ZLayer.FOCUS_SPRITE
                    display = Display.none
                }
            }
        }
        return Zones.FOCUS_SPRITE.resolve() ?: error("focus-sprite zone failed to mount")
    }
}
