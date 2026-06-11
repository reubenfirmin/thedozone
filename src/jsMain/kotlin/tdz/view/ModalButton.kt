package tdz.view

import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.div
import tdz.view.sync.SyncColors
import zoned.framework.dom.onClick
import zoned.framework.interop.css

/**
 * The one modal button. Primary = the theme accent as a filled call-to-action; secondary = a
 * recessed ghost button (see [SyncColors] for why these tokens and not e.g. columnFocusBorder,
 * which is near-white on some themes and made primary buttons unreadable).
 */
fun FlowContent.modalButton(
    text: String, primary: Boolean = false, enabled: Boolean = true, hint: String? = null, onPress: () -> Unit,
) {
    div {
        hint?.let { attributes["title"] = it }
        css {
            padding = Padding(vertical = 7.px, horizontal = 14.px)
            marginRight = 8.px
            cursor = if (enabled) Cursor.pointer else Cursor.default
            if (!enabled) opacity = 0.5
            borderRadius = 4.px
            fontSize = 13.px
            fontWeight = if (primary) FontWeight.w600 else FontWeight.normal
            textAlign = TextAlign.center
            userSelect = UserSelect.none
            backgroundColor = if (primary) SyncColors.primaryBg else SyncColors.buttonBg
            color = if (primary) SyncColors.primaryFg else SyncColors.buttonFg
            border = Border(
                1.px, BorderStyle.solid,
                if (primary) SyncColors.primaryBg else SyncColors.buttonBorder,
            )
        }
        if (enabled) onClick { onPress() }
        +text
    }
}
