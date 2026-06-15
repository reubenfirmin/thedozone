package tdz.view

import kotlinx.css.*
import kotlinx.css.properties.*
import kotlinx.html.*
import tdz.controller.DragDrop
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.SwitchToCanvas
import tdz.model.*
import tdz.model.ref
import tdz.model.card.*
import tdz.view.theme.FlourishStyle
import tdz.view.theme.MarkerStyle
import tdz.view.theme.ThemeManager
import tdz.view.theme.themedStyleSheet
import web.dom.document
import web.html.HTMLCanvasElement
import web.html.HTMLDivElement
import web.html.HTMLElement
import web.window.window
import zoned.framework.dom.Ref
import zoned.framework.dom.onClick
import zoned.framework.dom.onMount
import zoned.framework.dom.ref
import zoned.framework.interop.backgroundClipText
import zoned.framework.interop.containIntrinsicSize
import zoned.framework.interop.css
import zoned.framework.interop.getHtmlElement
import zoned.framework.interop.important
import zoned.framework.interop.webkitTextFillColor
import zoned.framework.interop.webkitTextStroke
import zoned.framework.libs.TileLayerOptions
import zoned.framework.libs.loadLeaflet
import zoned.framework.libs.loadNomnoml

/**
 * Cards -> Tags
 */
object CardRenderer {

    /** Sane ceiling on list nesting depth; parsing never exceeds 2, so beyond this is malformed. */
    private const val MAX_LIST_DEPTH = 32

    private val circledNumbers = listOf("⓪", "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩",
                                        "⑪", "⑫", "⑬", "⑭", "⑮", "⑯", "⑰", "⑱", "⑲", "⑳", "㉑",
                                        "㉒", "㉓", "㉔", "㉕", "㉖", "㉗", "㉘", "㉙", "㉚", "㉛", "㉜",
                                        "㉝", "㉞", "㉟", "㊱", "㊲", "㊳", "㊴", "㊵", "㊶", "㊷", "㊸",
                                        "㊹", "㊺", "㊻", "㊼", "㊽", "㊾", "㊿")

    /**
     * Render a stack of cards. [loose] opens up the vertical rhythm — more space between cards and
     * between the segments within a card — for reading-oriented surfaces like the help overlay,
     * which feel cramped at the app's default (dense) spacing.
     */
    fun FlowContent.render(canvasName: CanvasName, cards: List<Card>, eventBus: EventBus<Event>, loose: Boolean = false) {
        // The shared rules are theme-keyed: themedStyleSheet re-injects only on a theme change
        // (or a missing tag), so render passes after the first cost four no-op guard checks.
        ensureCardStyles()
        ensureHeadingStyles()
        ensureTaskStyles()
        ensureLinkStyles()
        for (card in cards) {
            render(canvasName, card, eventBus, loose)
        }
    }

    private fun FlowContent.render(canvasName: CanvasName, card: Card, eventBus: EventBus<Event>, loose: Boolean) {
        div(cardClasses(card, loose)) { buildCard(canvasName, card, eventBus, loose) }
    }

    /**
     * Single-card entry at consumer level (returns the element): lets a changed card be rebuilt IN
     * PLACE (`rebuildInPlace`) without re-rendering its column. Emits exactly the same DOM as the
     * list-level [render] — minus the shared `ensure*Styles()` injection, which the surrounding
     * render pass owns (a lone card rebuild can rely on them already being present).
     */
    fun TagConsumer<HTMLElement>.renderCard(
        canvasName: CanvasName,
        card: Card,
        eventBus: EventBus<Event>,
        loose: Boolean = false,
    ): HTMLElement = div(cardClasses(card, loose)) { buildCard(canvasName, card, eventBus, loose) }

    /**
     * The card's full chrome as modifier classes over the `.tdz-card` base rule — elevation,
     * chromeless h1-only title cards, the Material strip / TUI frame variants, loose spacing.
     * (All styling is theme-keyed rules; the element carries no inline style at all.)
     */
    private fun cardClasses(card: Card, loose: Boolean): String {
        val t = ThemeManager.current
        val chromeless = card.isH1Only()
        val imageOnly = card.isImageLink()
        val leadingHeading = card.segments.firstOrNull() is CardHeading
        return buildString {
            append(CssClasses.CARD)
            if (!chromeless && t.cardElevation.isNotEmpty()) append(" ${CssClasses.CARD_ELEV}")
            if (chromeless) append(" ${CssClasses.CARD_CHROMELESS}")
            else if (imageOnly) append(" ${CssClasses.CARD_IMAGE}")    // photo fills the card, no inner frame
            else if (t.titleInStrip && leadingHeading) append(" ${CssClasses.CARD_STRIP}")
            else if (t.titleInBorder && leadingHeading) append(" ${CssClasses.CARD_FRAMED}")
            if (loose) append(" ${CssClasses.CARD_LOOSE}")
        }
    }

    private fun DIV.buildCard(canvasName: CanvasName, card: Card, eventBus: EventBus<Event>, loose: Boolean) {
        val t = ThemeManager.current
        // TUI: a non-title card's leading heading becomes the panel's title, sitting in the top
        // border line (┌─ title ─┐). h1-only cards are excluded — they get the chromeless label
        // treatment below instead of an empty group-box frame.
        val frameTitle = if (t.titleInBorder && !card.isH1Only()) card.segments.firstOrNull() as? CardHeading else null
        // Material: a non-title card's leading heading goes into a full-bleed colored header strip.
        val stripTitle = if (t.titleInStrip && !card.isH1Only()) card.segments.firstOrNull() as? CardHeading else null
        // The chrome itself (fill, border, shadow, strip/frame/chromeless variants) is class rules
        // from [cardClasses] + [ensureCardStyles]; this builder only emits structure.
        val chromeless = card.isH1Only()
        id = DomIds.card(card.ref)
        // Marks a section header so drag-and-drop can carry the cards beneath it as a group.
        if (card.isH1Only()) attributes["data-h1only"] = "true"
        if (frameTitle != null) {
            span(CssClasses.CARD_FRAME_TITLE) {
                +frameTitle.text
            }
        }
        // Material header strip: the card title on a colored band across the top.
        if (stripTitle != null) {
            div(CssClasses.CARD_HEADER) { +stripTitle.text }
        }
        // When a strip is present the body needs its own padding (the card's was zeroed).
        val body: FlowContent.() -> Unit = {
            for (segment in card.segments) {
                if (segment === frameTitle || segment === stripTitle) continue
                if (loose) {
                    // Loose mode spaces segments apart (heading / text / quote sit too close by default).
                    div(CssClasses.LOOSE_SEG) {
                        renderSegment(canvasName, segment, card, eventBus)
                    }
                } else {
                    renderSegment(canvasName, segment, card, eventBus)
                }
            }
            // Ornamental centered divider at the foot of content cards (not h1-only title cards).
            if (t.cardFooterFlourish && !chromeless) div(CssClasses.CARD_FLOURISH) {}
        }
        if (stripTitle != null) div(CssClasses.CARD_BODY) { body() } else body()
    }

    private fun FlowContent.renderSegment(canvasName: CanvasName, segment: CardSegment, card: Card, eventBus: EventBus<Event>) {
        when (segment) {
            is ListGroup -> renderSegment(canvasName, segment, card, eventBus)
            is CardListItem -> throw Exception("Didn't expect ungrouped list item")
            is CardHeading -> renderSegment(segment)
            is CardText -> renderSegment(segment, card, eventBus)
            is Callout -> renderSegment(segment, card, eventBus)
            is CardCodeBlock -> renderRichText(segment)
            is Blockquote -> renderRichText(segment)
            is CardDiagram -> renderSegment(segment, card)
            else -> throw Exception("No handler for $segment")
        }
    }

    fun FlowContent.renderSegment(canvasName: CanvasName, segment: ListGroup, card: Card, eventBus: EventBus<Event>, depth: Int = 0) {
        // Hard recursion guard: normal parsing (toGroup) never nests deeper than 2, so any depth past
        // this is a malformed or cyclic group. Truncate and warn instead of recursing forever — an
        // unbounded list render once froze the tab for 22s and stack-overflowed, aborting the WHOLE
        // board render (the board went blank). A bad card must degrade to a truncated card, never a crash.
        if (depth > MAX_LIST_DEPTH) {
            console.warn("[render] list nesting exceeded $MAX_LIST_DEPTH on card '${card.tagId()}' — " +
                "truncating (the group is malformed/cyclic).")
            return
        }

        var index = 0

        fun renderListItems(group: ListGroup) {
            for (listMember in group.members) {
                if (listMember is CardListItem) {
                    renderSegment(canvasName, listMember, card, index++, eventBus)
                } else if (listMember is ListGroup) {
                    // recursive (depth-bounded above)
                    renderSegment(canvasName, listMember, card, eventBus, depth + 1)
                }
            }
        }

        if (segment.type == ListMemberType.NUMBERED) {
            ol {
                renderListItems(segment)
            }
        } else {
            ul {
                renderListItems(segment)
            }
        }
    }

    fun FlowContent.renderSegment(canvasName: CanvasName, segment: CardListItem, card: Card, index: Int, eventBus: EventBus<Event>) {
        div(CssClasses.TASK) {
            // Only the per-instance indent is inline; the row chrome lives in the .tdz-task rules
            // (one shared injection instead of a style-attribute build per row per render).
            if (segment.level > 0) css { paddingLeft = (6 + segment.level * 24).px }

            label {
                input(type = InputType.checkBox) {
                    value = segment.done.toString()
                }

                span(if (segment.done) "${CssClasses.MARKER} ${CssClasses.MARKER_DONE}" else CssClasses.MARKER) {
                    val ascii = ThemeManager.current.markerStyle == MarkerStyle.ASCII
                    if (segment.done) {
                        +(if (ascii) "[X]" else "\u2713")
                    } else {
                        if (segment.type == ListMemberType.NUMBERED) {
                            +(if (ascii) "${index + 1}." else circledNumbers[index])
                        } else if (segment.type == ListMemberType.BULLET) {
                            +(if (ascii) "[ ]" else "\u2610")
                        } else if (segment.type == ListMemberType.ARROW) {
                            +(if (ascii) ">" else when (segment.weight) {
                                1 -> "\u25B7"
                                2 -> "\u25B6"
                                3 -> "\u279C"
                                else -> "\u261B"
                            })
                        }
                    }
                }

                segment.text.forEach {
                    renderRichText(it, eventBus)
                }
            }
            // No onClick here: toggling is handled by ONE delegated listener per column
            // (CanvasColumns.wireTaskToggleDelegate), not a closure per row.
        }
    }

    /**
     * Inject/refresh the h1 rule from the theme via a typed [styleSheet] (a rule, not inline css{},
     * because it targets a class so every h1 picks it up). Refreshed so a theme switch updates it. The
     * gradient-clip variant uses the typed vendor-prefix extensions (backgroundClipText etc.).
     */
    private fun ensureHeadingStyles() {
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
    private fun ensureCardStyles() {
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
    private fun ensureLinkStyles() {
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
    private fun ensureTaskStyles() {
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
            rule(".${CssClasses.CALLOUT}") { padding = Padding(vertical = 1.px, horizontal = 6.px) }
        }
    }

    private fun FlowContent.renderSegment(segment: CardHeading) {
        when (segment.level) {
            1 -> {
                h1(CssClasses.H1) {
                    +segment.text
                }
            }

            2 -> h2(CssClasses.H2) {
                +segment.text
            }

            3 -> h3(CssClasses.H3) {
                +segment.text
            }

            4 -> h4(CssClasses.H4) {
                +segment.text
            }

            else -> p {
                +segment.text
            }
        }
    }

    private fun FlowContent.renderSegment(segment: CardText, card: Card, eventBus: EventBus<Event>) {
        div {
            segment.text.forEach { text ->
                renderRichText(text, eventBus)
            }
        }
    }

    private fun FlowContent.renderSegment(segment: Callout, card: Card, eventBus: EventBus<Event>) {
        div(CssClasses.CALLOUT) {
            // Row chrome from the .tdz-callout rule; only the per-instance indent is inline.
            if (segment.level > 0) css { paddingLeft = (6 + segment.level * 24).px }
            val ascii = ThemeManager.current.markerStyle == MarkerStyle.ASCII
            span(CssClasses.MARKER) {
                +(if (ascii) ">" else when (segment.weight) {
                    1 -> "▷"
                    2 -> "▶"
                    3 -> "➜"
                    else -> "☛"
                })
            }
            segment.text.forEach { renderRichText(it, eventBus) }
        }
    }

    private fun FlowContent.renderSegment(segment: CardDiagram, card: Card) {
        val t = ThemeManager.current
        // Derive nomnoml's colors from the theme so lines/arrowheads/text stay legible in every theme.
        // Previously these were hardcoded (stroke white, fill black) — fine on dark themes, but the white
        // strokes/arrows were invisible against a light card. stroke = text color, fill = card background.
        val diagramStroke = t.fg
        val diagramFill = t.cardBg
        val noml = listOf(
            "#stroke: $diagramStroke",
            "#fill: $diagramFill",
            segment.text,
        ).joinToString("\n")

        val canvasRef = Ref<HTMLCanvasElement>()
        canvas {
            id = card.tagId() + "_canvas"
            ref(canvasRef)
            // nomnoml sets the canvas's *intrinsic* (bitmap) size to the diagram's natural pixels and
            // never touches CSS. Forcing width:100% (the old `w-full`) stretched a small diagram to the
            // full column width, distorting it huge. Instead show it at natural size, scaling DOWN to fit
            // the card width while preserving aspect ratio.
            css {
                display = Display.block
                maxWidth = 100.pct
                height = LinearDimension.auto
            }
            // Draw only after the element is in the DOM. The DSL builds the canvas in-memory and the
            // framework appends it in finalize() *then* runs onMount; a synchronous getElementById
            // during construction would (and did) return null, so draw never ran and the canvas was blank.
            onMount {
                // nomnoml is code-split: fetched on the first card that actually has a diagram.
                loadNomnoml().then { nomnoml ->
                    nomnoml.draw(canvasRef.element, noml)
                }.catch { e ->
                    console.error("nomnoml draw failed", e)
                }
            }
        }
    }

    // empty segment
    private fun FlowContent.renderSegment() {
        p {}
    }

    private fun FlowContent.renderRichText(value: RichText, eventBus: EventBus<Event>) {
            when (value) {
            is CardCodeBlock -> renderRichText(value)
            is Blockquote -> renderRichText(value)
            is LinkText -> renderRichText(value, eventBus)
            is Text -> renderRichText(value)
        }
    }

    private fun FlowContent.renderRichText(codeBlock: CardCodeBlock) {
        pre {
            classes += "microlight"
            code {
                +codeBlock.text
            }
        }
    }

    /** A quote box (`> ...` lines) with a built-in copy button that copies the raw text. */
    private fun FlowContent.renderRichText(blockquote: Blockquote) {
        val theme = ThemeManager.current
        blockQuote {
            css {
                position = Position.relative
                margin = Margin(8.px, 0.px)
                padding = Padding(8.px, 40.px, 8.px, 12.px)   // right padding leaves room for the button
                borderLeft = Border(3.px, BorderStyle.solid, theme.menuBorder)
                backgroundColor = theme.blockquoteBg
                borderRadius = 3.px
                whiteSpace = WhiteSpace.preWrap
                fontFamily = "monospace"
            }
            button {
                attributes["aria-label"] = "Copy"
                +"⧉"
                attributes["title"] = "Copy"
                css {
                    position = Position.absolute
                    top = 4.px
                    right = 4.px
                    backgroundColor = Color.transparent
                    borderStyle = BorderStyle.none
                    cursor = Cursor.pointer
                    color = theme.fg
                    fontSize = 15.px
                }
                onClick {
                    web.navigator.navigator.clipboard.writeTextAsync(blockquote.text)
                }
            }
            +blockquote.text
        }
    }

    private fun FlowContent.renderRichText(linkText: LinkText, eventBus: EventBus<Event>) {
            when (linkText) {
            is CanvasLink -> renderRichText(linkText, eventBus)
            is ExternalLink -> renderRichText(linkText)
            is ImageLink -> renderRichText(linkText)
            is MapLink -> renderRichText(linkText)
        }
    }

    private fun FlowContent.renderRichText(link: MapLink) {
        val mapRef = Ref<HTMLDivElement>()
        div {
            ref(mapRef)
            css {
                width = 100.pct
                height = (link.height ?: 200).px
            }
            onMount {
                // Leaflet (and its CSS) are code-split: fetched on the first card with a map.
                loadLeaflet().then { leaflet ->
                    if (!mapRef.element.isConnected) return@then   // card re-rendered while loading
                    val map = leaflet.map(mapRef.element)
                    leaflet.tileLayer(
                        "https://{s}.tile.osm.org/{z}/{x}/{y}.png",
                        object : TileLayerOptions {
                            override var attribution: String? =
                                "&copy; <a href=\"https://osm.org/copyright\">OpenStreetMap</a> contributors"
                            override var maxZoom: Int? = 19
                            override var minZoom: Int? = null
                        },
                    ).addTo(map)
                    map.setView(arrayOf(link.lat, link.long), link.zoom ?: 12)
                    map.invalidateSize()
                }.catch {
                    // Map failed to initialize (e.g. chunk/tile/network issue); leave the container
                    // empty rather than breaking the rest of the card.
                }
            }
        }
    }

    private fun FlowContent.renderRichText(text: Text) {
        span {
            +text.text
        }
    }

    private fun FlowContent.renderRichText(link: CanvasLink, eventBus: EventBus<Event>) {
        val linkId = DomIds.canvasLink(link.canvasName)

        span(CssClasses.DROP_ZONE) {
            attributes["data-canvas-name"] = link.canvasName.normalized()
            id = linkId
            css { display = Display.inlineBlock }

            // Fancy chip: bordered pill in the link color with a nav arrow; hover brightens + arcade shadow.
            a(CssClasses.CANVAS_LINK) {
                css {
                    cursor = Cursor.pointer
                    display = Display.inlineBlock
                    margin = Margin(vertical = 10.px, horizontal = 8.px)   // breathing room around the badge
                    marginLeft = 0.px                                      // left-justified (no gap on the left)
                    padding = Padding(vertical = 2.px, horizontal = 8.px)
                    border = Border(1.px, BorderStyle.solid, ThemeManager.current.link)
                    borderRadius = ThemeManager.current.cardRadius
                    backgroundColor = ThemeManager.current.linkBg
                    color = ThemeManager.current.link
                    fontWeight = FontWeight.bold
                }
                +"❯❯ ${link.label}"
                // The canvas route is a catch-all (/{canvas...}); build the URL directly rather
                // than Route.path (which requires a fixed param count). Real nav is the onClick.
                href = "/${link.canvasName.normalized()}"

                onClick { event ->
                    eventBus.fire(SwitchToCanvas(link.canvasName))
                    event.preventDefault()
                    event.stopPropagation()
                }
            }
        }
    }

    private fun FlowContent.renderRichText(link: ImageLink) {
        span {
            img {
                attributes["crossorigin"] = "anonymous"
                src = link.url
            }
        }
    }

    private fun FlowContent.renderRichText(link: ExternalLink) {
        span {
            // Style it as a web link so it's visibly distinct from plain text (and from the
            // canvas-link chip): theme link color, underline, pointer cursor.
            css {
                color = ThemeManager.current.link
                textDecoration = TextDecoration(setOf(TextDecorationLine.underline))
                cursor = Cursor.pointer
            }
            +link.label
            onClick { event ->
                val url = link.url
                if (url.startsWith("www.")) {
                    window.open("https://${url}")
                } else {
                    window.open(url)
                }
                event.preventDefault()
                event.stopPropagation()
                event.stopImmediatePropagation()
            }
        }
    }
}
