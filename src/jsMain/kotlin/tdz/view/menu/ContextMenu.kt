package tdz.view.menu

import kotlinx.browser.window as domWindow
import kotlinx.css.*
import kotlinx.css.properties.BoxShadow
import kotlinx.html.CommonAttributeGroupFacade
import kotlinx.html.FlowContent
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.span
import web.dom.Node
import web.dom.document
import web.events.EventType
import web.events.addEventListener
import web.events.removeEventListener
import web.html.HTMLElement
import web.html.HTMLStyleElement
import web.keyboard.KeyboardEvent
import web.mouse.MouseEvent
import web.window.window
import zoned.framework.dom.Ref
import zoned.framework.dom.onClick
import zoned.framework.dom.onContextMenu
import zoned.framework.dom.onMount
import zoned.framework.dom.onMouseLeave
import zoned.framework.dom.ref
import zoned.framework.interop.addToBody
import zoned.framework.interop.css
import tdz.view.theme.ThemeManager

/**
 * Single active floating context menu. Renders typesafe [MenuSection]s into one body-level
 * container positioned at the cursor, dismissed on mouse-leave / outside mousedown / Escape.
 * Frontend-only zoned-kotlinjs (no htmx, no server).
 *
 * Rendering model: items are CHILDREN of the menu div (built on the `FlowContent` receiver);
 * element events use the DSL handlers (live listeners via `onTagEvent`); styling is typed
 * `css { }`; `onMount` handles post-append work (keep-on-screen measurement, document
 * dismiss listeners). Item hover is pure CSS — one `:hover` rule injected once, applied as a
 * class to enabled items.
 */
object ContextMenu {

    private const val ITEM_CLASS = "tdz-ctx-item"
    private const val SUBMENU_CLASS = "tdz-ctx-submenu"
    private const val SUBMENU_PARENT_CLASS = "tdz-ctx-submenu-parent"

    /** Transparent hit-area (px) around the menu so a small mouse-out doesn't dismiss it. */
    private const val MENU_BUFFER = 20


    private var stylesInjected = false
    private var menuEl: HTMLElement? = null
    private var outsideHandler: ((MouseEvent) -> Unit)? = null
    private var keyHandler: ((KeyboardEvent) -> Unit)? = null
    private var onKeyHandler: ((KeyboardEvent) -> Boolean)? = null

    /**
     * @param onKey optional extra key handler (e.g. number-key quick-select). Returning true means
     *   the key was consumed: the menu closes and the event's default is prevented.
     */
    fun show(x: Int, y: Int, sections: List<MenuSection>, onKey: ((KeyboardEvent) -> Boolean)? = null) {
        hide()
        onKeyHandler = onKey
        ensureStyles()
        val menuRef = Ref<HTMLElement>()
        addToBody {
            // Outer wrapper: a transparent MENU_BUFFER-px catch area around the visible menu, so the
            // mouse can stray a little past the menu without it instantly closing. mouse-leave / the
            // outside-click "contains" check key off this wrapper, so the buffer counts as "in" the menu.
            div {
                ref(menuRef)
                css {
                    position = Position.fixed
                    left = (x - MENU_BUFFER).px
                    top = (y - MENU_BUFFER).px
                    zIndex = 9999
                    padding = Padding(MENU_BUFFER.px)   // transparent (no background on the wrapper)
                }
                onMouseLeave { hide() }
                div {   // the visible menu
                    css {
                        minWidth = 180.px
                        padding = Padding(vertical = 8.px, horizontal = 0.px)
                        backgroundColor = ThemeManager.current.menuBg
                        color = ThemeManager.current.menuFg
                        border = Border(1.px, BorderStyle.solid, ThemeManager.current.menuBorder)
                        borderRadius = ThemeManager.current.cardRadius
                        boxShadow += BoxShadow(ThemeManager.current.shadow, 0.px, 2.px, 8.px)
                    }
                    renderSections(sections)
                }
                onMount {
                    val el = menuRef.element
                    menuEl = el
                    // Position the wrapper so the visible menu's top-left still lands at the cursor.
                    keepOnScreen(el, x - MENU_BUFFER, y - MENU_BUFFER)
                    installDismissHandlers()
                }
            }
        }
    }

    fun hide() {
        outsideHandler?.let { document.removeEventListener(EventType("mousedown"), it) }
        keyHandler?.let { document.removeEventListener(EventType("keydown"), it) }
        outsideHandler = null
        keyHandler = null
        onKeyHandler = null
        menuEl?.remove()
        menuEl = null
    }

    private fun FlowContent.renderSections(sections: List<MenuSection>) {
        sections.forEachIndexed { index, section ->
            section.title?.let { title ->
                div {
                    css {
                        padding = Padding(vertical = 4.px, horizontal = 12.px)
                        color = ThemeManager.current.menuTitle
                    }
                    +title
                }
            }
            val iconRow = section.items.isNotEmpty() && section.items.all { it.icon != null }
            if (iconRow) {
                div {
                    css {
                        display = Display.flex
                        flexDirection = FlexDirection.row
                        padding = Padding(vertical = 4.px, horizontal = 12.px)
                    }
                    section.items.forEach { item -> renderIconItem(item) }
                }
            } else {
                // Items inside a titled section are indented under their heading.
                val indented = section.title != null
                section.items.forEach { item -> renderItem(item, indented) }
            }
            if (index < sections.size - 1) {
                div {
                    css {
                        height = 1.px
                        backgroundColor = ThemeManager.current.menuBorder
                        marginTop = 4.px
                        marginBottom = 4.px
                    }
                }
            }
        }
    }

    private fun FlowContent.renderItem(item: MenuItem, indented: Boolean) {
        if (item.submenu != null) {
            renderSubmenuItem(item, indented)
            return
        }
        div {
            css {
                padding = Padding(vertical = 4.px, horizontal = 12.px)
                if (indented) paddingLeft = 24.px
                if (item.enabled) {
                    cursor = Cursor.pointer
                } else {
                    cursor = Cursor.default
                    color = ThemeManager.current.menuDisabled
                }
            }
            if (item.enabled) {
                classes = setOf(ITEM_CLASS)   // CSS :hover (see ensureStyles)
                onClick {
                    item.onSelect()
                    hide()
                }
            }
            +((if (item.active) "✓ " else "") + item.label + (item.shortcut?.let { " [$it]" } ?: ""))
        }
    }

    /** An item that opens a flyout of nested items on hover (CSS show/hide; JS keeps it on-screen). */
    private fun FlowContent.renderSubmenuItem(item: MenuItem, indented: Boolean) {
        val parentRef = Ref<HTMLElement>()
        val subRef = Ref<HTMLElement>()
        div {
            ref(parentRef)
            css {
                position = Position.relative
                display = Display.flex
                justifyContent = JustifyContent.spaceBetween
                alignItems = Align.center
                padding = Padding(vertical = 4.px, horizontal = 12.px)
                if (indented) paddingLeft = 24.px
                cursor = Cursor.pointer
            }
            classes = setOf(ITEM_CLASS, SUBMENU_PARENT_CLASS)
            +item.label
            span { css { paddingLeft = 12.px }; +"▸" }   // ▸
            div {
                ref(subRef)
                classes = setOf(SUBMENU_CLASS)   // hidden until parent :hover (see ensureStyles)
                css {
                    position = Position.absolute
                    left = 100.pct
                    top = (-9).px                // align the flyout top with the row's top padding
                    minWidth = 160.px
                    padding = Padding(vertical = 8.px, horizontal = 0.px)
                    backgroundColor = ThemeManager.current.menuBg
                    color = ThemeManager.current.menuFg
                    border = Border(1.px, BorderStyle.solid, ThemeManager.current.menuBorder)
                    borderRadius = ThemeManager.current.cardRadius
                    boxShadow += BoxShadow(ThemeManager.current.shadow, 0.px, 2.px, 8.px)
                    zIndex = 10000
                }
                item.submenu?.forEach { renderItem(it, false) }
            }
            // On hover, re-measure (next frame, once :hover has shown the flyout) and flip/shift it on-screen.
            onMount {
                val parent = parentRef.element
                val sub = subRef.element
                val enter: (MouseEvent) -> Unit = { domWindow.requestAnimationFrame { positionSubmenu(sub) } }
                parent.addEventListener(EventType("mouseenter"), enter)
            }
        }
    }

    /** Keep a hovered submenu flyout within the viewport: flip to the left if it overruns the right
     *  edge, and shift up if it overruns the bottom. */
    private fun positionSubmenu(sub: HTMLElement) {
        sub.style.left = "100%"; sub.style.right = "auto"
        sub.style.top = "-9px"; sub.style.bottom = "auto"
        if (sub.getBoundingClientRect().right > window.innerWidth) {
            sub.style.left = "auto"; sub.style.right = "100%"
        }
        val r = sub.getBoundingClientRect()
        if (r.bottom > window.innerHeight) {
            sub.style.top = "${(-9 - (r.bottom - window.innerHeight)).toInt()}px"
        }
    }

    private fun FlowContent.renderIconItem(item: MenuItem) {
        div {
            css {
                padding = Padding(vertical = 4.px, horizontal = 4.px)
                marginRight = 6.px
                cursor = if (item.enabled) Cursor.pointer else Cursor.default
                if (!item.enabled) opacity = 0.4
            }
            attributes["title"] = item.label
            if (item.enabled) {
                classes = setOf(ITEM_CLASS)
                onClick {
                    item.onSelect()
                    hide()
                }
            }
            item.icon?.invoke(this)
        }
    }

    /** Inject the hover rule once. Pure CSS :hover (typed body), applied via [ITEM_CLASS]. */
    private fun ensureStyles() {
        if (stylesInjected) return
        stylesInjected = true
        val hoverBody = CssBuilder().apply {
            backgroundColor = ThemeManager.current.menuHover
            fontWeight = FontWeight.bold
        }.toString().trim()
        val styleEl = document.createElement("style") as HTMLStyleElement
        styleEl.textContent = """
            .$ITEM_CLASS:hover { $hoverBody }
            .$SUBMENU_CLASS { display: none; }
            .$SUBMENU_PARENT_CLASS:hover > .$SUBMENU_CLASS { display: block; }
        """.trimIndent()
        document.head.appendChild(styleEl)
    }

    /** Nudge the menu fully on-screen after it's measured. Dynamic position => element.style. */
    private fun keepOnScreen(el: HTMLElement, x: Int, y: Int) {
        val rect = el.getBoundingClientRect()
        var left = x.toDouble()
        var top = y.toDouble()
        if (rect.right > window.innerWidth) left = x - (rect.right - window.innerWidth)
        if (rect.bottom > window.innerHeight) top = y - (rect.bottom - window.innerHeight)
        el.style.left = "${maxOf(4.0, left).toInt()}px"   // never off the left/top edge
        el.style.top = "${maxOf(4.0, top).toInt()}px"
    }

    private fun installDismissHandlers() {
        val outside: (MouseEvent) -> Unit = { event ->
            val target = event.target as? Node
            val el = menuEl
            if (el != null && (target == null || !el.contains(target))) {
                hide()
            }
        }
        val key: (KeyboardEvent) -> Unit = { event ->
            if (onKeyHandler?.invoke(event) == true) {
                event.preventDefault()
                hide()
            } else if (event.key == "Escape") {
                hide()
            }
        }
        outsideHandler = outside
        keyHandler = key
        document.addEventListener(EventType("mousedown"), outside)
        document.addEventListener(EventType("keydown"), key)
    }
}

/**
 * Open a context menu on right-click of this element. [resolve] receives the event so the
 * menu can be context-sensitive. Uses the DSL `onContextMenu` handler (binds a live listener).
 */
fun CommonAttributeGroupFacade.attachContextMenu(resolve: (MouseEvent) -> List<MenuSection>) {
    onContextMenu { event ->
        event.preventDefault()
        ContextMenu.show(event.clientX, event.clientY, resolve(event))
    }
}
