package tdz.view

import kotlinx.html.div
import tdz.controller.events.Event
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.markdown.MarkdownService
import tdz.view.theme.ThemeManager
import tdz.view.theme.dosTheme
import tdz.view.theme.longFormTheme
import web.dom.document
import web.html.HTMLElement
import web.dom.getComputedStyle
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The card render path's styling was hoisted from per-element inline css{} (a CssBuilder + style
 * attribute write per element per render) into shared theme-keyed class rules; only per-instance
 * values (the indent) stay inline. These assertions pin the COMPUTED result of that composition —
 * rule base + inline override — so the hoist can't silently drop what cards look like.
 */
class CardStyleHoistTest {

    @AfterTest
    fun cleanup() {
        document.body.asDynamic()?.innerHTML = ""
    }

    private fun renderColumn(markdown: String) {
        val cards = MarkdownService.fromMd(markdown, ColumnId("1"))
        addToBody {
            div {
                with(CardRenderer) {
                    render(CanvasName("test"), cards, EventBus<Event>())
                }
            }
        }
    }

    private fun computed(selector: String): (String) -> String {
        val el = document.querySelector(selector) as? HTMLElement ?: error("no element for $selector")
        val style = getComputedStyle(el)
        return { prop -> style.getPropertyValue(prop) }
    }

    @Test
    fun taskRowKeepsItsRowStylingFromTheSharedRule() {
        renderColumn("# Tasks\n* top level\n")
        val row = computed(".tdz-task")
        assertEquals("pointer", row("cursor"), "row stays a click target")
        assertEquals("4px", row("border-radius"))
        assertEquals("6px", row("padding-left"), "level-0 indent comes from the shared rule")
        assertEquals("1px", row("padding-top"))
    }

    @Test
    fun nestedTaskIndentComposesInlineOverInheritedRule() {
        renderColumn("# Tasks\n* parent\n  * nested child\n")   // 2 spaces = one level
        val rows = document.querySelectorAll(".tdz-task")
        assertEquals(2, rows.length, "both tasks render")
        val nested = getComputedStyle(rows[1] as HTMLElement)
        assertEquals("30px", nested.getPropertyValue("padding-left"), "per-instance indent (6 + level*24) overrides the rule")
    }

    @Test
    fun contentCardCarriesThemeChromeAndTitleCardStaysChromeless() {
        renderColumn("# Section\n\n# Card\nbody text\n")
        val all = document.querySelectorAll(".tdz-card")
        assertEquals(2, all.length)
        val title = getComputedStyle(all[0] as HTMLElement)     // h1-only → chromeless
        val content = getComputedStyle(all[1] as HTMLElement)
        // blueRoom's cardBg is deliberately transparent, so the chrome that distinguishes a content
        // card from a chromeless title card under the default theme is the hard offset shadow.
        assertTrue(content.getPropertyValue("box-shadow") != "none",
            "content card carries the theme card shadow (got '${content.getPropertyValue("box-shadow")}')")
        assertEquals("none", title.getPropertyValue("box-shadow"), "title card carries no shadow")
        assertEquals("rgba(0, 0, 0, 0)", title.getPropertyValue("background-color"), "h1-only title card sits directly on the column")
    }

    @Test
    fun calloutMarkerSharesTheMarkerRule() {
        renderColumn("# C\n- a callout line\n")
        val marker = computed(".tdz-callout > span")
        assertEquals("8px", marker("padding-right"))
        assertEquals("inline-block", marker("display"))
    }

    /**
     * Long-column scaling: offscreen cards must be skippable (content-visibility: auto) with a
     * remembered placeholder size (contain-intrinsic-size: auto + estimate) so render/layout cost
     * is proportional to the viewport, not the column length, and the scrollbar stays stable.
     */
    @Test
    fun cardsAreSkippableOffscreen() {
        renderColumn("# Card\nbody text\n")
        val card = computed(".tdz-card")
        assertEquals("auto", card("content-visibility"))
        assertTrue("auto" in card("contain-intrinsic-size") && "px" in card("contain-intrinsic-size"),
            "skipped cards need a remembered/estimated intrinsic size (got '${card("contain-intrinsic-size")}')")
    }

    /**
     * Regression (DOS theme): `content-visibility: auto` applies PAINT containment at all times —
     * not only while skipped — so the TUI frame title straddling the card's top border (-11px) was
     * clipped on every card. Framed cards must opt out; headingless cards keep the optimization.
     */
    @Test
    fun dosFramedCardsOptOutOfContainmentSoTheBorderTitleIsNotClipped() {
        val original = ThemeManager.current
        try {
            ThemeManager.use(dosTheme)
            renderColumn("# Framed title\nbody under a framed heading\n\nplain body card without a heading\n")
            val framed = computed(".tdz-card-framed")
            assertEquals("visible", framed("content-visibility"),
                "a framed card must not be paint-contained — its title paints above the border")
            val cards = document.querySelectorAll(".tdz-card")
            val plain = getComputedStyle(cards[1] as HTMLElement)
            assertEquals("auto", plain.getPropertyValue("content-visibility"),
                "cards without a border-straddling title keep the skip optimization")
        } finally {
            ThemeManager.use(original)
        }
    }

    /** DOS's ALL-CAPS h1 now comes from theme DATA (headingTransform et al), not a titleInBorder
     *  branch — this pins that the data path actually produces the TUI look. */
    @Test
    fun dosHeadingTypographyComesFromThemeData() {
        val original = ThemeManager.current
        try {
            ThemeManager.use(dosTheme)
            renderColumn("# section label\n")
            val h1 = computed(".tdz-h1")
            assertEquals("uppercase", h1("text-transform"))
        } finally {
            ThemeManager.use(original)
        }
    }

    /** Long Form's section break is the three-dot glyph, not the line-and-diamond. */
    @Test
    fun longFormDividerIsTheThreeDotGlyph() {
        val original = ThemeManager.current
        try {
            ThemeManager.use(longFormTheme)
            renderColumn("# Card\nbody text\n")
            val flourish = document.querySelector(".tdz-card-flourish") as? HTMLElement
            assertNotNull(flourish, "content cards carry the divider")
            val before = getComputedStyle(flourish, "::before")
            assertTrue("·" in before.getPropertyValue("content"),
                "the ::before carries the dots (got '${before.getPropertyValue("content")}')")
        } finally {
            ThemeManager.use(original)
        }
    }

    @Test
    fun h2HeadingStyledViaClassRule() {
        renderColumn("# Card\n## Sub heading\ntext\n")
        val h2 = document.querySelector("h2") as? HTMLElement
        assertNotNull(h2)
        val style = getComputedStyle(h2)
        assertEquals("4px", style.getPropertyValue("padding-bottom"), "h2 keeps its rhythm from the shared rule")
    }
}
