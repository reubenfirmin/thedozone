package tdz.view

import kotlinx.css.*
import kotlinx.css.properties.*
import kotlinx.html.*
import tdz.controller.DragDrop
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.SwitchToCanvas
import tdz.resolveCanvasLinkTarget
import tdz.model.*
import tdz.model.ref
import tdz.model.card.*
import tdz.view.theme.MarkerStyle
import tdz.view.theme.ThemeManager
import web.dom.document
import web.html.HTMLElement
import web.window.window
import zoned.framework.dom.onClick
import zoned.framework.interop.css
import zoned.framework.interop.getHtmlElement

/**
 * Cards -> Tags
 */
object CardRenderer {

    /** Hard ceiling on list nesting; a malformed or cyclic group is truncated past this depth. */
    private const val MAX_LIST_DEPTH = 32

    /**
     * Render a stack of cards. [loose] opens up the vertical rhythm — more space between cards and
     * between the segments within a card — for reading-oriented surfaces like the help overlay,
     * which feel cramped at the app's default (dense) spacing.
     */
    fun FlowContent.render(canvasName: CanvasName, cards: List<Card>, eventBus: EventBus<Event>, loose: Boolean = false) {
        // The shared rules are theme-keyed: themedStyleSheet re-injects only on a theme change
        // (or a missing tag), so render passes after the first cost four no-op guard checks.
        CardStyles.ensureCardStyles()
        CardStyles.ensureHeadingStyles()
        CardStyles.ensureTaskStyles()
        CardStyles.ensureLinkStyles()
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
            // A link-only card is a compact nav chip, not a prose block (drops margin/padding/flourish).
            if (card.isCanvasLink()) append(" ${CssClasses.CARD_LINK}")
            if (loose) append(" ${CssClasses.CARD_LOOSE}")
        }
    }

    private fun DIV.buildCard(canvasName: CanvasName, card: Card, eventBus: EventBus<Event>, loose: Boolean) {
        val t = ThemeManager.current
        // A card with `//` face boundaries is a FLIP card: show one side at a time with a top-right
        // flip affordance. It skips the TUI/Material "leading-heading-into-the-chrome" treatment —
        // each side renders its own headings inline.
        val sides = card.segments.splitOnSideBreak()
        if (sides.size > 1) {
            id = DomIds.card(card.ref)
            // The flip widget owns the turn animation and its CSS; it asks us to fill each side.
            with(FlipCard) {
                buildFlipCard(sides.size) { i ->
                    for (segment in sides[i]) {
                        if (loose) div(CssClasses.LOOSE_SEG) { renderSegment(canvasName, segment, card, eventBus) }
                        else renderSegment(canvasName, segment, card, eventBus)
                    }
                }
            }
            return
        }
        // TUI: a non-title card's leading heading becomes the panel's title, sitting in the top
        // border line (┌─ title ─┐). h1-only cards are excluded — they get the chromeless label
        // treatment below instead of an empty group-box frame.
        val frameTitle = if (t.titleInBorder && !card.isH1Only()) card.segments.firstOrNull() as? CardHeading else null
        // Material: a non-title card's leading heading goes into a full-bleed colored header strip.
        val stripTitle = if (t.titleInStrip && !card.isH1Only()) card.segments.firstOrNull() as? CardHeading else null
        // The chrome itself (fill, border, shadow, strip/frame/chromeless variants) is class rules
        // from [cardClasses] + [CardStyles.ensureCardStyles]; this builder only emits structure.
        val chromeless = card.isH1Only()
        id = DomIds.card(card.ref)
        // Marks a section header so drag-and-drop can carry the cards beneath it as a group.
        if (card.isH1Only()) attributes[DomIds.DATA_H1_ONLY] = "true"
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
            if (t.cardFooterFlourish && !chromeless && !card.isCanvasLink()) div(CssClasses.CARD_FLOURISH) {}
        }
        if (stripTitle != null) div(CssClasses.CARD_BODY) { body() } else body()
    }

    private fun FlowContent.renderSegment(canvasName: CanvasName, segment: CardSegment, card: Card, eventBus: EventBus<Event>) {
        when (segment) {
            is ListGroup -> renderSegment(canvasName, segment, card, eventBus)
            is CardListItem -> console.warn("[render] unexpected ungrouped list item — skipping")
            is CardHeading -> renderSegment(segment)
            is CardText -> renderSegment(canvasName, segment, card, eventBus)
            is Callout -> renderSegment(canvasName, segment, card, eventBus)
            is CardCodeBlock -> renderRichText(segment)
            is Blockquote -> renderRichText(segment)
            is CardDiagram -> with(CardDiagramRenderer) { renderDiagram(segment, card) }
            else -> console.warn("[render] no segment handler for $segment — skipping")
        }
    }

    fun FlowContent.renderSegment(canvasName: CanvasName, segment: ListGroup, card: Card, eventBus: EventBus<Event>, depth: Int = 0) {
        // Hard recursion guard: a malformed or cyclic group must not recurse forever — that would
        // abort the WHOLE board render, not just this card. Truncate and warn instead; a bad card
        // degrades to a truncated card, never a crash.
        if (depth > MAX_LIST_DEPTH) {
            console.warn("[render] list nesting exceeded $MAX_LIST_DEPTH on card '${DomIds.card(card.ref)}' — " +
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

                val ascii = ThemeManager.current.markerStyle == MarkerStyle.ASCII
                val markerClass = buildString {
                    append(CssClasses.MARKER)
                    if (segment.done) append(" ${CssClasses.MARKER_DONE}")
                    // Non-ASCII numbered items get a CSS circle badge (plain digit padded inside a drawn
                    // ring), not a cramped, font-fallback-janky Unicode circled glyph.
                    else if (!ascii && segment.type == ListMemberType.NUMBERED) append(" ${CssClasses.MARKER_NUM}")
                }
                span(markerClass) {
                    if (segment.done) {
                        +(if (ascii) "[X]" else "\u2713")
                    } else {
                        if (segment.type == ListMemberType.NUMBERED) {
                            // 1-based plain digit; the circle ring + internal padding is .tdz-marker-num.
                            +(if (ascii) "${index + 1}." else "${index + 1}")
                        } else if (segment.type == ListMemberType.BULLET) {
                            +(if (ascii) "[ ]" else "\u2610")
                        }
                    }
                }

                segment.text.forEach {
                    renderRichText(canvasName, it, eventBus)
                }
            }
            // No onClick here: toggling is handled by ONE delegated listener per column
            // (CanvasColumns.wireTaskToggleDelegate), not a closure per row.
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

    private fun FlowContent.renderSegment(canvasName: CanvasName, segment: CardText, card: Card, eventBus: EventBus<Event>) {
        div {
            segment.text.forEach { text ->
                renderRichText(canvasName, text, eventBus)
            }
        }
    }

    private fun FlowContent.renderSegment(canvasName: CanvasName, segment: Callout, card: Card, eventBus: EventBus<Event>) {
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
            segment.text.forEach { renderRichText(canvasName, it, eventBus) }
        }
    }

    private fun FlowContent.renderRichText(canvasName: CanvasName, value: RichText, eventBus: EventBus<Event>) {
            when (value) {
            is CardCodeBlock -> renderRichText(value)
            is Blockquote -> renderRichText(value)
            is LinkText -> renderRichText(canvasName, value, eventBus)
            is Text -> renderRichText(value)
        }
    }

    private fun FlowContent.renderRichText(codeBlock: CardCodeBlock) {
        val theme = ThemeManager.current
        pre {
            classes += CssClasses.MICROLIGHT
            // position for the absolute copy button; right padding keeps it clear of the code.
            css {
                position = Position.relative
                paddingRight = 40.px
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
                    web.navigator.navigator.clipboard.writeTextAsync(codeBlock.text)
                }
            }
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

    private fun FlowContent.renderRichText(canvasName: CanvasName, linkText: LinkText, eventBus: EventBus<Event>) {
            when (linkText) {
            is CanvasLink -> renderRichText(canvasName, linkText, eventBus)
            is ExternalLink -> renderRichText(linkText)
            is ImageLink -> renderRichText(linkText)
            is MapLink -> with(CardMap) { renderMap(linkText) }
        }
    }

    private fun FlowContent.renderRichText(text: Text) {
        span {
            +text.text
        }
    }

    private fun FlowContent.renderRichText(canvasName: CanvasName, link: CanvasLink, eventBus: EventBus<Event>) {
        val linkId = DomIds.canvasLink(link.unresolvedName)
        val absoluteTarget = resolveCanvasLinkTarget(canvasName, link.target)

        span(CssClasses.DROP_ZONE) {
            // The id uses the bare leaf so element ids never contain "/" (fragile in CSS selectors).
            // The data-canvas-name carries the full absolute path so DragDrop can route the drop
            // to the same board that the click navigates to (absoluteTarget, not the bare leaf).
            attributes[DomIds.DATA_CANVAS_NAME] = absoluteTarget.normalized()
            id = linkId
            css { display = Display.inlineBlock }

            // Fancy chip: bordered pill in the link color with a nav arrow; hover brightens + arcade shadow.
            a(CssClasses.CANVAS_LINK) {
                css {
                    cursor = Cursor.pointer
                    display = Display.inlineBlock
                    margin = Margin(vertical = 8.px, horizontal = 8.px)    // breathing room around the badge
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
                href = "/${absoluteTarget.normalized()}"

                onClick { event ->
                    eventBus.fire(SwitchToCanvas(absoluteTarget))
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
