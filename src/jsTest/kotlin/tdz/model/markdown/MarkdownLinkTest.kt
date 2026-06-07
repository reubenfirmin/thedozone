package tdz.model.markdown

import tdz.model.card.CanvasLink
import tdz.model.card.ExternalLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A bracketed token is a *web* link when it carries an explicit web indicator (a `://` scheme,
 * a `www.` prefix, or a path `/`); otherwise it is a canvas link. Regression: a bare domain such
 * as `[www.google.com]` used to fall through to a canvas link because the only "external" test was
 * "contains a slash".
 */
class MarkdownLinkTest {

    private fun firstLink(line: String) =
        MarkdownLine(line).elements().filterIsInstance<tdz.model.card.LinkText>().first()

    @Test
    fun wwwPrefixedDomainIsExternalLink() {
        val link = firstLink("[www.google.com]")
        assertTrue(link is ExternalLink, "a www-prefixed domain should be a web link, was $link")
        assertEquals("www.google.com", link.url)
    }

    @Test
    fun schemeQualifiedUrlIsExternalLink() {
        val link = firstLink("[https://google.com]")
        assertTrue(link is ExternalLink, "an https URL should be a web link, was $link")
        assertEquals("https://google.com", link.url)
    }

    @Test
    fun mailtoSchemeIsExternalLink() {
        val link = firstLink("[mailto:rf@4rc.io]")
        assertTrue(link is ExternalLink, "a mailto: URL should be a web link, was $link")
        assertEquals("mailto:rf@4rc.io", link.url)
    }

    @Test
    fun bareDomainWithoutSchemeStaysCanvasLink() {
        // Deliberate product decision: a bare `google.com` (no scheme, no www., no slash) is treated
        // as a canvas name, not a web link, so canvas names with dots keep working.
        val link = firstLink("[google.com]")
        assertTrue(link is CanvasLink, "a bare dotted name should stay a canvas link, was $link")
    }

    @Test
    fun plainNameIsStillCanvasLink() {
        val link = firstLink("[My Other Canvas]")
        assertTrue(link is CanvasLink, "a plain name with no web indicator should stay a canvas link, was $link")
    }
}
