package tdz.view.sync

import kotlinx.css.*
import kotlinx.css.properties.*
import kotlinx.html.FlowContent
import kotlinx.html.TagConsumer
import kotlinx.html.div
import tdz.view.ZLayer
import tdz.view.theme.ThemeManager
import web.html.HTMLElement
import zoned.framework.dom.Ref
import zoned.framework.dom.onClick
import zoned.framework.dom.ref
import zoned.framework.interop.css

/**
 * The shared scrim + dialog "chrome" for the sync modals ([SyncModal], [LocalFolderModal]). Renders a
 * full-viewport scrim that dismisses on backdrop click, centring a themed dialog box around [content].
 *
 * [scrimRef] is bound to the scrim element so the click handler can tell a backdrop click (target ==
 * scrim) from a click inside the dialog; the same ref is what each modal reads as its `rootEl`.
 * [onBackdropClick] runs only for backdrop clicks. [content] is invoked with the dialog div as its
 * `FlowContent` receiver, so callers render their body (title, fields, buttons, onMount) inside it.
 */
internal fun TagConsumer<HTMLElement>.syncModalChrome(
    scrimRef: Ref<HTMLElement>,
    onBackdropClick: () -> Unit,
    minWidth: LinearDimension = 360.px,
    maxWidth: LinearDimension? = null,
    pad: LinearDimension = 20.px,
    content: FlowContent.() -> Unit,
) {
    div {   // scrim
        ref(scrimRef)
        css {
            position = Position.fixed
            top = 0.px; left = 0.px; right = 0.px; bottom = 0.px
            zIndex = ZLayer.MODAL
            display = Display.flex
            alignItems = Align.center
            justifyContent = JustifyContent.center
            backgroundColor = SyncColors.modalOverlay
        }
        onClick { e -> if (e.target == scrimRef.element) onBackdropClick() }
        div {   // dialog
            css {
                this.minWidth = minWidth
                maxWidth?.let { this.maxWidth = it }
                padding = Padding(pad)
                backgroundColor = ThemeManager.current.menuBg
                color = ThemeManager.current.menuFg
                border = Border(1.px, BorderStyle.solid, ThemeManager.current.menuBorder)
                borderRadius = 8.px
                boxShadow = BoxShadows().apply { this += BoxShadow(ThemeManager.current.shadow, 0.px, 12.px, 36.px) }
            }
            content()
        }
    }
}
