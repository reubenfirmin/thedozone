package tdz.view.menu

import kotlinx.browser.document
import org.w3c.dom.asList
import tdz.view.theme.ThemeManager
import tdz.view.theme.themes
import kotlin.test.Test
import kotlin.test.assertTrue

class ContextMenuStylesTest {

    /** All injected hover-rule text for menu items (normally a single <style>). */
    private fun hoverStyles(): String =
        document.head?.querySelectorAll("style")?.asList()
            ?.mapNotNull { it.textContent }
            ?.filter { it.contains(".tdz-ctx-item:hover") }
            ?.joinToString("\n") ?: ""

    @Test
    fun hoverRuleFollowsThemeChanges() {
        val initial = ThemeManager.current
        try {
            val blueRoom = themes.first { it.name == "Blue Room" }
            val dos = themes.first { it.name == "DOS" }
            val sections = listOf(MenuSection(items = listOf(MenuItem("Item"))))

            ThemeManager.use(blueRoom)
            ContextMenu.show(10, 10, sections)
            ContextMenu.hide()
            assertTrue(
                hoverStyles().contains(blueRoom.menuHover.toString()),
                "hover rule should use Blue Room's menuHover",
            )

            ThemeManager.use(dos)
            ContextMenu.show(10, 10, sections)
            ContextMenu.hide()
            assertTrue(
                hoverStyles().contains(dos.menuHover.toString()),
                "hover rule should follow an in-session switch to the DOS theme",
            )
        } finally {
            ThemeManager.use(initial)
        }
    }
}
