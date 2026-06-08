package tdz.view.menu

import kotlinx.html.FlowContent

/**
 * A single selectable menu entry.
 *
 * Most entries are text ([label]). An entry may instead carry an [icon] — a typed DSL block
 * that draws the entry's glyph (e.g. a CSS-drawn split icon). A section whose entries all have
 * icons is rendered as a horizontal icon row; [label] is then used as the hover title.
 *
 * An entry with a [submenu] opens a flyout of nested entries on hover (rendered with a "▸").
 */
data class MenuItem(
    val label: String,
    val shortcut: String? = null,   // e.g. "F1" -> rendered as "[F1]"
    val enabled: Boolean = true,    // stubs render disabled (greyed, no handler)
    val active: Boolean = false,    // current-selection indicator (e.g. active theme) -> underline
    val icon: (FlowContent.() -> Unit)? = null,
    val submenu: List<MenuItem>? = null,
    val onSelect: () -> Unit = {},
)

/** A group of items, optionally titled, separated from other sections by a divider. */
data class MenuSection(
    val title: String? = null,
    val items: List<MenuItem>,
)
