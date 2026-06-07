package tdz.view.menu

import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.div
import tdz.model.Split
import tdz.view.theme.ThemeManager
import zoned.framework.interop.css

/**
 * A CSS-drawn split icon: a small bordered box divided into [panes] equal panes —
 * side-by-side for [Split.VERTICAL] (vertical dividers) or stacked for [Split.HORIZONTAL]
 * (horizontal dividers). 2 panes reads as "even", 3 as "distribute".
 */
fun splitIcon(panes: Int, split: Split): FlowContent.() -> Unit = {
    val sideBySide = split == Split.VERTICAL
    div {
        css {
            display = Display.flex
            flexDirection = if (sideBySide) FlexDirection.row else FlexDirection.column
            width = 18.px
            height = 14.px
            border = Border(1.px, BorderStyle.solid, ThemeManager.current.icon)
            borderRadius = 2.px
            overflow = Overflow.hidden
        }
        repeat(panes) { i ->
            div {
                css {
                    flexGrow = 1.0
                    backgroundColor = ThemeManager.current.iconFaint
                }
            }
            if (i < panes - 1) {
                div {
                    css {
                        if (sideBySide) width = 1.px else height = 1.px
                        backgroundColor = ThemeManager.current.icon
                    }
                }
            }
        }
    }
}
