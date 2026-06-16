package tdz.view

import kotlinx.css.*
import kotlinx.css.properties.*
import tdz.view.theme.FlourishStyle
import tdz.view.theme.ThemeManager
import tdz.view.theme.themedStyleSheet
import zoned.framework.interop.backgroundClipText
import zoned.framework.interop.containIntrinsicSize
import zoned.framework.interop.important
import zoned.framework.interop.webkitTextFillColor
import zoned.framework.interop.webkitTextStroke

/**
 * The themed CSS for cards — headings, card chrome, canvas-link chips, and task rows — injected once
 * per theme via [themedStyleSheet] (re-injected only on a theme change). Split out of [CardRenderer]
 * so that file stays focused on building DOM; these are pure, theme-keyed stylesheet emitters with no
 * dependency on the render functions. Flip-card styles stay in [CardRenderer] (coupled to its flip
 * animation constants).
 */
object CardStyles {

    /**
     * Inject/refresh the h1 rule from the theme via a typed [styleSheet] (a rule, not inline css{},
     * because it targets a class so every h1 picks it up). Refreshed so a theme switch updates it. The
     * gradient-clip variant uses the typed vendor-prefix extensions (backgroundClipText etc.).
     */
    fun ensureHeadingStyles() {
        val theme = ThemeManager.current
        themedStyleSheet("tdz-heading-styles") {
            rule(".${CssClasses.H2}") {
                fontFamily = theme.headingFont
                fontSize = theme.heading2FontSize     // 1em in a text-grid theme; larger elsewhere
                fontWeight = FontWeight(theme.heading2FontWeight.toString())  // ignored by single-weight bitmap fonts (DOS)
                if (theme.headingTransform == "lowercase") textTransform = TextTransform.lowercase
                color = theme.heading2
                paddingBottom = 4.px
            }
            rule(".${CssClasses.H3}") {
                fontFamily = theme.headingFont
                if (theme.headingTransform == "lowercase") textTransform = TextTransform.lowercase
                fontSize = 1.125.rem
                fontWeight = FontWeight.bold
                paddingBottom = 8.px
            }
            rule(".${CssClasses.H4}") {
                fontFamily = theme.headingFont
                if (theme.headingTransform == "lowercase") textTransform = TextTransform.lowercase
                fontSize = 1.125.rem
                paddingBottom = 8.px
            }
            rule(".${CssClasses.H1}") {
                fontFamily = theme.headingFont
                fontWeight = FontWeight("${theme.heading1FontWeight}")
                lineHeight = LineHeight("1.05")
                margin = Margin(0.px)
                paddingTop = 0.1.em
                paddingBottom = theme.heading1PadBottom
                letterSpacing = theme.headingLetterSpacing
                // Pure data — case/tracking/size all come from theme tokens (DOS sets uppercase +
                // wide tracking there; no theme-identity branching here).
                textTransform = TextTransform.valueOf(theme.headingTransform)
                fontSize = theme.heading1FontSize
                // Data-driven (not theme-identity): if the theme supplies a heading gradient, clip it to
                // the text (remotestorage style); otherwise a monochrome fill dressed with a same-hue
                // glow + hairline stroke.
                if (theme.headingGradient.isNotEmpty()) {
                    backgroundImage = Image(theme.headingGradient)
                    backgroundClipText()
                    webkitTextFillColor(Color.transparent)
                    color = Color.transparent
                } else {
                    color = theme.heading1
                    webkitTextStroke(theme.headingStrokeWidth, theme.heading1)
                    textShadow += TextShadow(theme.headingGlow, 0.px, 0.px, 26.px)
                    textShadow += TextShadow(theme.headingDropShadow, 0.px, 1.px, 1.px)
                }
            }
        }
    }

    /** Inject/refresh the card chrome rules — the `.tdz-card` base (fill, border, shadow, spacing)
     *  and its modifiers (elevation + :hover lift, chromeless title cards, Material strip, TUI
     *  frame, loose spacing), plus the ornamental ::before footer divider. */
    fun ensureCardStyles() {
        val t = ThemeManager.current
        themedStyleSheet("tdz-card-styles") {
            rule(".${CssClasses.CARD}") {
                lineHeight = LineHeight(t.lineHeight)
                margin = Margin(t.cardMargin)
                padding = Padding(t.cardPadding)
                border = Border(t.borderWidth, BorderStyle.solid, t.cardBorder)
                backgroundColor = t.cardBg
                borderRadius = t.cardRadius
                // hard, no-blur offset = 2D "arcade"/DOS dialog shadow (transparent in DOS = flat).
                // Overridden by .tdz-card-elev (soft elevation) and .tdz-card-chromeless (none) below.
                boxShadow += BoxShadow(t.cardShadow, 5.px, 5.px, 0.px)
                // Offscreen cards skip layout + paint entirely, so a long column costs what the
                // viewport shows, not what the column holds. The intrinsic estimate stands in only
                // until a card has rendered once (auto = the browser then remembers its real size).
                // NOTE: content-visibility:auto applies PAINT containment at all times (size
                // containment is the only skip-conditional part), clipping any descendant that
                // paints outside the border box — .tdz-card-framed opts back out below for exactly
                // that reason (its title straddles the top border).
                contentVisibility = ContentVisibility.auto
                containIntrinsicSize(120.px)
            }
            // Loose mode adds breathing room between stacked cards (e.g. the help overlay), and
            // between the segments within them (the .tdz-loose-seg wrappers).
            rule(".${CssClasses.CARD_LOOSE}") { marginBottom = 24.px }
            rule(".${CssClasses.LOOSE_SEG}") { marginBottom = 12.px }
            // A link-only card is a nav chip, not a prose block: drop the prose margin/padding, and
            // opt out of content-visibility (its intrinsic-size placeholder reserved a tall box around
            // the tiny chip), so a stack of links is tight instead of acres of whitespace.
            rule(".${CssClasses.CARD_LINK}") {
                // Tight vertically, but keep the theme's horizontal margin AND padding so the chip
                // lines up with the left edge of regular cards' content (not flush to the column).
                margin = Margin(vertical = 4.px, horizontal = t.cardMargin)
                padding = Padding(vertical = 2.px, horizontal = t.cardPadding)
                contentVisibility = ContentVisibility.visible
            }
            // A card that is nothing but an image renders the photo full-bleed: no margin, no
            // padding, no frame — just the picture, clipped to the card's own rounded corners.
            rule(".${CssClasses.CARD_IMAGE}") {
                margin = Margin(0.px)
                padding = Padding(0.px)
                borderStyle = BorderStyle.none
                overflow = Overflow.hidden
            }
            // The image lives inside a text wrapper (div) then a span; flatten both so nothing
            // around the picture adds space, and let the image fill the card edge to edge.
            rule(".${CssClasses.CARD_IMAGE} div, .${CssClasses.CARD_IMAGE} span") {
                display = Display.block
                margin = Margin(0.px)
                padding = Padding(0.px)
            }
            rule(".${CssClasses.CARD_IMAGE} img") {
                display = Display.block
                width = 100.pct
            }
            // Material header strip variant: full-bleed band, so the card zeroes its own padding
            // (the .tdz-card-body supplies it back) and clips the strip to the rounded corners.
            if (t.titleInStrip) {
                rule(".${CssClasses.CARD_STRIP}") {
                    padding = Padding(0.px)
                    overflow = Overflow.hidden
                    // Drop the card border so the colored header bleeds flush to the top edge — a
                    // (transparent) border otherwise leaves a 1-2px line of the column showing above it.
                    borderStyle = BorderStyle.none
                }
            }
            // TUI frame variant: the title straddles the top border line.
            if (t.titleInBorder) {
                rule(".${CssClasses.CARD_FRAMED}") {
                    position = Position.relative
                    paddingTop = 16.px   // clear the title that straddles the top border
                    // The base rule's content-visibility:auto paint containment would clip the
                    // title's above-the-border half on EVERY card (not just skipped ones); framed
                    // cards trade the skip optimization for an intact frame.
                    contentVisibility = ContentVisibility.visible
                }
                rule(".${CssClasses.CARD_FRAME_TITLE}") {
                    position = Position.absolute
                    top = (-11).px             // straddle the top border line (tuned for 19px font)
                    left = 8.px
                    padding = Padding(horizontal = 4.px, vertical = 0.px)
                    backgroundColor = t.panelBg   // cover the border segment behind the title
                    color = t.heading1
                    whiteSpace = WhiteSpace.nowrap
                }
            }
            // Soft/blurred elevation (Material) — css{} only does a single hard offset, so it's a rule.
            // A transition + optional deeper hover shadow give the Material "lift".
            if (t.cardElevation.isNotEmpty()) {
                rule(".${CssClasses.CARD_ELEV}") {
                    t.cardElevation.forEach { boxShadow += it }
                    transition += Transition("box-shadow", 0.18.s, Timing.ease)
                }
                if (t.cardElevationHover.isNotEmpty()) {
                    rule(".${CssClasses.CARD_ELEV}:hover") { t.cardElevationHover.forEach { boxShadow += it } }
                }
            }
            // An h1-only "title" card sits chrome-free on the column background — no fill, border,
            // or shadow — so the heading reads as a section label, in every theme (DOS renders it
            // ALL CAPS via the heading rule). Drag visuals are unaffected: the mirror/ghost force
            // their own bg. Placed after the elevation rules so it wins the cascade.
            rule(".${CssClasses.CARD_CHROMELESS}") {
                border = Border.none
                backgroundColor = Color.transparent
                boxShadow = BoxShadows.none
                // DOS: the section label sits on the column bg with breathing room above it.
                if (t.titleInBorder) paddingTop = 12.px
            }
            // Card-footer divider, glyph set per theme: an elegant line-with-diamond, or Medium's
            // centered three-dot section break.
            if (t.cardFooterFlourish) when (t.flourishStyle) {
                FlourishStyle.LINE_DIAMOND -> {
                    // A short centered line (~14% of the card) with a centered diamond (the ::before),
                    // tinted via the muted icon color.
                    rule(".${CssClasses.CARD_FLOURISH}") {
                        position = Position.relative
                        height = 1.px
                        width = 14.pct
                        minWidth = 44.px
                        marginTop = 1.5.em
                        marginBottom = 0.6.em
                        marginLeft = LinearDimension.auto
                        marginRight = LinearDimension.auto
                        backgroundColor = t.icon
                    }
                    rule(".${CssClasses.CARD_FLOURISH}::before") {
                        content = QuotedString("")
                        position = Position.absolute
                        left = 50.pct
                        top = (-4).px
                        width = 8.px
                        height = 8.px
                        marginLeft = (-4).px
                        backgroundColor = t.icon
                        transform { rotate(45.deg) }
                    }
                }
                FlourishStyle.DOTS -> {
                    rule(".${CssClasses.CARD_FLOURISH}") {
                        textAlign = TextAlign.center
                        marginTop = 1.2.em
                        marginBottom = 0.4.em
                        color = t.icon
                    }
                    rule(".${CssClasses.CARD_FLOURISH}::before") {
                        content = QuotedString("· · ·")
                        letterSpacing = 0.35.em
                        fontSize = 1.1.em
                    }
                }
            }
            // Material header strip: a full-bleed colored band carrying the card title, with the body
            // padded back in (the card itself zeroes its padding when a strip is present).
            if (t.titleInStrip) {
                rule(".${CssClasses.CARD_HEADER}") {
                    backgroundColor = t.cardHeaderBg
                    color = t.cardHeaderFg
                    fontFamily = t.headingFont
                    fontWeight = FontWeight("500")
                    fontSize = 1.05.rem
                    padding = Padding(vertical = 12.px, horizontal = 16.px)
                }
                rule(".${CssClasses.CARD_BODY}") { padding = Padding(t.cardPadding) }
            }
        }
    }

    /** Inject/refresh canvas-link chip styling — no underline, and a hover (brighten + arcade shadow). */
    fun ensureLinkStyles() {
        val t = ThemeManager.current
        themedStyleSheet("tdz-link-styles") {
            rule(".${CssClasses.CANVAS_LINK}") { textDecoration = TextDecoration.none }
            rule(".${CssClasses.CANVAS_LINK}:hover") {
                important("background-color", t.linkHoverBg)   // must beat the chip's base fill
                boxShadow += BoxShadow(t.cardShadow, 2.px, 2.px, 0.px)   // hard arcade offset
            }
        }
    }

    /**
     * Inject/refresh the task-row rules: row chrome + hover highlight, the visually-hidden
     * checkbox, and the marker glyph (shared with callouts). All static per theme — only the
     * per-instance indent stays inline on the row.
     */
    fun ensureTaskStyles() {
        val t = ThemeManager.current
        themedStyleSheet("tdz-task-styles") {
            rule(".${CssClasses.TASK}") {
                cursor = Cursor.pointer
                // minimal vertical padding for density; the hover highlight still fills the full-width row
                padding = Padding(vertical = 1.px, horizontal = 6.px)
                borderRadius = 4.px
            }
            rule(".${CssClasses.TASK}:hover") { backgroundColor = t.taskHover }
            rule(".${CssClasses.TASK} label") { position = Position.relative; cursor = Cursor.pointer }
            rule(".${CssClasses.TASK} input") { position = Position.absolute; opacity = 0.0; cursor = Cursor.pointer }
            rule(".${CssClasses.MARKER}") {
                display = Display.inlineBlock
                paddingRight = 0.5.rem
                fontWeight = FontWeight.bold
            }
            rule(".${CssClasses.MARKER_DONE}") { color = t.checkDone }
            // Numbered marker = a drawn circle badge holding the plain digit, padded inside the ring
            // (a Unicode circled digit can't be padded and font-fallbacks unevenly). currentColor keeps
            // the ring in the marker's own text color.
            rule(".${CssClasses.MARKER_NUM}") {
                display = Display.inlineFlex
                alignItems = Align.center
                justifyContent = JustifyContent.center
                boxSizing = BoxSizing.borderBox
                minWidth = 1.55.em
                height = 1.55.em
                padding = Padding(horizontal = 0.38.em)   // room inside the ring (and for 2-digit numbers)
                marginRight = 0.45.rem                     // gap to the text (overrides MARKER's paddingRight)
                border = Border(1.5.px, BorderStyle.solid, Color("currentColor"))
                borderRadius = 50.pct
                fontSize = 0.92.em
                lineHeight = LineHeight("1")
                verticalAlign = VerticalAlign.middle
            }
            rule(".${CssClasses.CALLOUT}") { padding = Padding(vertical = 1.px, horizontal = 6.px) }
        }
    }
}
