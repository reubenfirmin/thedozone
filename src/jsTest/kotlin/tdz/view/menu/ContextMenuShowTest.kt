package tdz.view.menu

import web.dom.document
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies ContextMenu.show() actually renders a menu into the DOM — exercising the
 * addToBody + ref() + positionAt() + section/item rendering path that the app uses on
 * right-click. Isolates "menu doesn't appear" to show() vs. event wiring.
 */
class ContextMenuShowTest {

    @AfterTest
    fun cleanup() {
        ContextMenu.hide()
    }

    @Test
    fun showRendersMenuIntoBody() {
        ContextMenu.show(
            10, 10,
            listOf(
                MenuSection(
                    title = "Backups & Export",
                    items = listOf(
                        MenuItem(label = "Download", onSelect = {}),
                        MenuItem(label = "Clear Done", enabled = false),
                    ),
                ),
            ),
        )
        val bodyText = document.body.textContent ?: ""
        assertTrue(bodyText.contains("Download"), "menu should render into body; got: '$bodyText'")
        assertTrue(bodyText.contains("Backups & Export"), "section title should render; got: '$bodyText'")
    }

    @Test
    fun enabledItemsCarryHoverClass() {
        ContextMenu.show(5, 5, listOf(MenuSection(items = listOf(MenuItem("Go", onSelect = {})))))
        // The CSS :hover effect is applied via this class on enabled items.
        assertTrue(document.querySelector(".tdz-ctx-item") != null, "enabled item should carry the hover class")
    }
}
