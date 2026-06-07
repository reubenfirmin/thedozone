package tdz.view.menu

import kotlinx.html.div
import zoned.framework.dom.onClick
import zoned.framework.dom.onContextMenu
import zoned.framework.interop.addToBody
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test for the kotlinx-html-web <-> kotlin-wrappers linkage break.
 *
 * Setting an event handler through the zoned/kotlinx-html DSL invokes kotlinx-html's
 * internal `setEvent`, which calls `web.events.addEventListener`. When the published
 * `kotlinx-html 0.12.0-web` klib was compiled against kotlin-wrappers 2025.11.12 but the
 * app linked kotlin-wrappers 2026.6.2, that symbol no longer resolved and Kotlin/JS
 * partial linkage threw `IrLinkageError` at runtime the moment any handler was set
 * (e.g. the context menu's `onContextMenu`). After rebuilding the fork against 2026.6.2
 * this must not throw.
 */
class EventHandlerLinkageTest {

    @Test
    fun settingDslEventHandlersDoesNotThrowLinkageError() {
        addToBody {
            div {
                onClick { }
                onContextMenu { }
            }
        }
        // Reaching this line means setEvent -> web.events.addEventListener linked successfully.
        assertTrue(true)
    }
}
