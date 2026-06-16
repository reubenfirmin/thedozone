package tdz.model.markdown

import tdz.model.card.CanvasLink
import tdz.model.card.ExternalLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A bracketed token is a *web* link when it carries an explicit web indicator (a `://` scheme or a
 * `www.` prefix); otherwise it is a canvas link. In particular, a path containing `/` (e.g.
 * `[/a/b/c]`, `[sub/child]`) is now a canvas link — the old "contains slash => web" rule has been
 * dropped. Regression: a bare domain such as `[www.google.com]` must still be an external link.
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
        // Deliberate product decision: a bare `google.com` (no scheme, no www.) is treated
        // as a canvas name, not a web link, so canvas names with dots keep working.
        val link = firstLink("[google.com]")
        assertTrue(link is CanvasLink, "a bare dotted name should stay a canvas link, was $link")
    }

    @Test
    fun plainNameIsStillCanvasLink() {
        val link = firstLink("[My Other Canvas]")
        assertTrue(link is CanvasLink, "a plain name with no web indicator should stay a canvas link, was $link")
    }

    @Test
    fun absolutePathIsCanvasLink() {
        // [/a/new/hierarchy] is a canvas link now — not a web link — since we dropped the slash rule.
        val raw = firstLink("[/a/new/hierarchy]")
        assertTrue(raw is CanvasLink, "an absolute path should be a canvas link, was $raw")
        assertEquals("/a/new/hierarchy", raw.target)
    }

    @Test
    fun relativePathIsCanvasLink() {
        val raw = firstLink("[sub/child]")
        assertTrue(raw is CanvasLink, "a relative multi-segment path should be a canvas link, was $raw")
        assertEquals("sub/child", raw.target)
    }

    @Test
    fun dotDotIsCanvasLink() {
        val raw = firstLink("[..]")
        assertTrue(raw is CanvasLink, "[..] should be a canvas link, was $raw")
        assertEquals("..", raw.target)
    }

    @Test
    fun tildeIsCanvasLink() {
        val raw = firstLink("[~]")
        assertTrue(raw is CanvasLink, "[~] should be a canvas link, was $raw")
        assertEquals("~", raw.target)
    }

    @Test
    fun rootSlashIsCanvasLink() {
        val raw = firstLink("[/]")
        assertTrue(raw is CanvasLink, "[/] should be a canvas link (root nav), was $raw")
        assertEquals("/", raw.target)
    }

    @Test
    fun httpUrlWithPathIsExternalLink() {
        // A full http URL with a path must stay as an ExternalLink — the scheme check catches it.
        val link = firstLink("[http://example.com/path]")
        assertTrue(link is ExternalLink, "an http URL with a path should be an external link, was $link")
    }
}
