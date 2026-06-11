package tdz.view.theme

import kotlinx.css.Color
import kotlinx.css.color
import web.dom.ElementId
import web.dom.document
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [themedStyleSheet] is the injection guard for theme-keyed rules: hot paths call their ensure*()
 * on every render pass (cards) or pointer event (wormhole portal), and each unguarded call rewrote
 * the <style> tag's textContent — a global style-recalc trigger for content that hadn't changed.
 * The guard re-injects only when the active theme differs from the one that produced the rules.
 */
class ThemedStyleSheetTest {

    private val original = ThemeManager.current

    @AfterTest
    fun restoreTheme() {
        ThemeManager.use(original)
        document.getElementById(ElementId("tss-test"))?.remove()
    }

    private fun styleText(): String =
        document.getElementById(ElementId("tss-test"))?.textContent ?: ""

    @Test
    fun sameThemeDoesNotRewriteTheStyleTag() {
        themedStyleSheet("tss-test") { rule(".tss-a") { color = Color.red } }
        document.getElementById(ElementId("tss-test"))!!.textContent = "SENTINEL"
        themedStyleSheet("tss-test") { rule(".tss-a") { color = Color.red } }
        assertEquals("SENTINEL", styleText(), "an unchanged theme must not re-inject (no recalc churn)")
    }

    @Test
    fun themeChangeReinjects() {
        themedStyleSheet("tss-test") { rule(".tss-a") { color = Color.red } }
        document.getElementById(ElementId("tss-test"))!!.textContent = "SENTINEL"
        ThemeManager.use(themes.first { it !== ThemeManager.current })
        themedStyleSheet("tss-test") { rule(".tss-b") { color = Color.red } }
        assertNotEquals("SENTINEL", styleText(), "a theme switch must rewrite the rules")
        assertTrue(".tss-b" in styleText())
    }

    @Test
    fun removedStyleTagIsReinjectedDespiteTheGuard() {
        themedStyleSheet("tss-test") { rule(".tss-a") { color = Color.red } }
        document.getElementById(ElementId("tss-test"))!!.remove()
        themedStyleSheet("tss-test") { rule(".tss-a") { color = Color.red } }
        assertTrue(".tss-a" in styleText(), "a missing tag must be rebuilt even under the same theme")
    }
}
