package tdz.view

import kotlinx.css.outlineOffset
import kotlinx.css.px
import tdz.view.theme.DragPreviewFill
import tdz.view.theme.Theme
import tdz.view.theme.themedStyleSheet
import zoned.framework.interop.important

/**
 * Inject/refresh the drag styling from [theme]. The drop placeholder (ghostClass) is a solid
 * rectangle with its contents hidden; the floating mirror (dragClass) is opaque with a solid
 * background + shadow. Colors come from the typed theme (interpolated into the rule — an injected
 * stylesheet is inherently a string, but the source stays typed).
 *
 * Owned by the view layer so the drag controller doesn't reach into stylesheet emission itself; the
 * controller calls this with the active [Theme] whenever drag styling must be (re)injected.
 */
fun injectDragStyles(theme: Theme) {
    // AppBackground paints the page background fixed to the viewport, so the preview cuts
    // straight through the (translucent) column to the real background; Image paints a custom
    // image on the preview itself (e.g. a spotlight); Solid = the dragPlaceholder color alone.
    val phImg = when (val fill = theme.dragPlaceholderFill) {
        DragPreviewFill.Solid -> "none"
        DragPreviewFill.AppBackground -> theme.appBgImage
        is DragPreviewFill.Image -> fill.css
    }
    val phAttach = if (theme.dragPlaceholderFill == DragPreviewFill.AppBackground) "fixed" else "scroll"
    // !important throughout: these must beat Sortable's inline styles and the cards' own
    // inline css{}.
    themedStyleSheet("tdz-drag-styles") {
        rule(".${CssClasses.CARD_GHOST}") {
            important("background-color", theme.dragPlaceholder)
            important("background-image", phImg)
            important("background-attachment", phAttach)
            important("background-size", "cover")
            important("border-color", theme.dragPlaceholder)
            important("border-radius", "0")
            important("opacity", "1")
            important("box-shadow", "none")
        }
        rule(".${CssClasses.CARD_GHOST} *") { important("visibility", "hidden") }
        rule(".${CssClasses.CARD_CHOSEN}") { important("background-color", theme.dragMirrorBg) }
        rule(".${CssClasses.CARD_DRAG}") {
            important("opacity", "1")
            important("background-color", theme.dragMirrorBg)
            important("border-radius", "0")
            important("box-shadow", "5px 5px 0 ${theme.cardShadow}")
        }
        rule(".${CssClasses.CARD_SELECTED}") {
            important("outline", "2px dashed ${theme.dragPlaceholder}")
            outlineOffset = (-2).px
        }
        // Group drag: the mirror becomes a transparent container holding clones of the real cards
        // (each clone keeps its own background/border/shadow), so it reads as the actual stack
        // of cards moving. The placeholder is grown to the group height inline.
        rule(".${CssClasses.CARD_DRAG_GROUP}") {
            important("background-color", "transparent")
            important("background-image", "none")
            important("box-shadow", "none")
            important("padding", "0")
        }
        // Each cloned card gets the opaque drag background (themes like rainbow use translucent
        // card fills, which would show the page through the floating stack).
        rule(".${CssClasses.CARD_DRAG_GROUP} .${CssClasses.CARD}") {
            important("background-color", theme.dragMirrorBg)
            important("background-image", "none")
        }
    }
}
