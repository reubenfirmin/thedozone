package tdz.view

import kotlinx.css.*
import kotlinx.css.properties.*
import kotlinx.html.*
import tdz.App
import tdz.controller.DragDrop
import tdz.controller.events.EventBus
import tdz.controller.events.TaskToggleDone
import tdz.controller.events.SwitchToCanvas
import tdz.model.*
import tdz.model.card.*
import tdz.tdzDragDrop
import tdz.view.theme.MarkerStyle
import tdz.view.theme.ThemeManager
import web.dom.ElementId
import web.dom.document
import web.html.HTMLCanvasElement
import web.html.HTMLDivElement
import web.html.HTMLStyleElement
import web.window.window
import zoned.framework.dom.Ref
import zoned.framework.dom.onClick
import zoned.framework.dom.onMount
import zoned.framework.dom.ref
import zoned.framework.interop.backgroundClipText
import zoned.framework.interop.css
import zoned.framework.interop.getHtmlElement
import zoned.framework.interop.important
import zoned.framework.interop.styleSheet
import zoned.framework.interop.webkitTextFillColor
import zoned.framework.interop.webkitTextStroke
import zoned.framework.libs.Leaflet
import zoned.framework.libs.TileLayerOptions
import zoned.framework.libs.draw

/**
 * Cards -> Tags
 */
object CardRenderer {

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
    fun FlowContent.render(canvasName: CanvasName, cards: List<Card>, eventBus: EventBus, loose: Boolean = false) {
        console.log("CardRenderer.render called with ${cards.size} cards for canvas $canvasName")
        for (card in cards) {
            render(canvasName, card, eventBus, loose)
        }
    }

    private fun FlowContent.render(canvasName: CanvasName, card: Card, eventBus: EventBus, loose: Boolean) {
        val t = ThemeManager.current
        // TUI: a non-title card's leading heading becomes the panel's title, sitting in the top
        // border line (┌─ title ─┐). h1-only cards are excluded — they get the chromeless label
        // treatment below instead of an empty group-box frame.
        val frameTitle = if (t.titleInBorder && !card.isH1Only()) card.segments.firstOrNull() as? CardHeading else null
        // Material: a non-title card's leading heading goes into a full-bleed coloured header strip.
        val stripTitle = if (t.titleInStrip && !card.isH1Only()) card.segments.firstOrNull() as? CardHeading else null
        // An h1-only "title" card sits chrome-free on the column background — no fill, border, or
        // shadow — so the heading reads as a section label, in every theme (DOS renders it ALL CAPS
        // via the heading rule). Drag visuals are unaffected: the mirror/ghost force their own bg.
        val chromeless = card.isH1Only()
        // Themes that supply cardElevation (e.g. Material elevation) get it via an injected class
        // rule instead of the default hard offset; chromeless title cards never carry a shadow.
        val elevated = !chromeless && t.cardElevation.isNotEmpty()
        div(if (elevated) "tdz-card tdz-card-elev" else "tdz-card") {
            ensureCardStyles()   // injects only the bits css{} can't express (line-height, elevation, flourish, header)
            css {
                margin = Margin(t.cardMargin)
                // Loose mode adds breathing room between stacked cards (e.g. the help overlay).
                if (loose) marginBottom = 24.px
                // A header strip is full-bleed, so its card drops top padding (the strip supplies it).
                padding = if (stripTitle != null) Padding(0.px) else Padding(t.cardPadding)
                if (chromeless) {
                    // DOS: the section label sits on the column bg with breathing room above it.
                    if (t.titleInBorder) paddingTop = 12.px
                } else {
                    border = Border(t.borderWidth, BorderStyle.solid, t.cardBorder)
                    backgroundColor = t.cardBg
                    borderRadius = t.cardRadius
                    if (stripTitle != null) overflow = Overflow.hidden   // clip the strip to the rounded corners
                    // hard, no-blur offset = 2D "arcade"/DOS dialog shadow (transparent in DOS = flat).
                    // Skipped when the theme supplies its own (soft) elevation via cardElevation.
                    if (!elevated) boxShadow += BoxShadow(t.cardShadow, 5.px, 5.px, 0.px)
                    if (frameTitle != null) {
                        position = Position.relative
                        paddingTop = 16.px   // clear the title that straddles the top border
                    }
                }
            }
            id = "card-${card.metadata.columnId}-${card.index}"
            // Marks a section header so drag-and-drop can carry the cards beneath it as a group.
            if (card.isH1Only()) attributes["data-h1only"] = "true"
            if (frameTitle != null) {
                span {
                    css {
                        position = Position.absolute
                        top = (-11).px             // straddle the top border line (tuned for 19px font)
                        left = 8.px
                        padding = Padding(horizontal = 4.px, vertical = 0.px)
                        backgroundColor = t.panelBg   // cover the border segment behind the title
                        color = t.heading1
                        whiteSpace = WhiteSpace.nowrap
                    }
                    +frameTitle.text
                }
            }
            // Material header strip: the card title on a coloured band across the top.
            if (stripTitle != null) {
                div("tdz-card-header") { +stripTitle.text }
            }
            // When a strip is present the body needs its own padding (the card's was zeroed).
            val body: FlowContent.() -> Unit = {
                for (segment in card.segments) {
                    if (segment === frameTitle || segment === stripTitle) continue
                    if (loose) {
                        // Loose mode spaces segments apart (heading / text / quote sit too close by default).
                        div {
                            css { marginBottom = 12.px }
                            renderSegment(canvasName, segment, card, eventBus)
                        }
                    } else {
                        renderSegment(canvasName, segment, card, eventBus)
                    }
                }
                // Ornamental centered divider at the foot of content cards (not h1-only title cards).
                if (t.cardFooterFlourish && !chromeless) div("tdz-card-flourish") {}
            }
            if (stripTitle != null) div("tdz-card-body") { body() } else body()
        }
    }

    private fun FlowContent.renderSegment(canvasName: CanvasName, segment: CardSegment, card: Card, eventBus: EventBus) {
        when (segment) {
            is ListGroup -> renderSegment(canvasName, segment, card, eventBus)
            is CardListItem -> throw Exception("Didn't expect ungrouped list item")
            is CardHeading -> renderSegment(segment)
            is CardText -> renderSegment(segment, card, eventBus)
            is Callout -> renderSegment(segment, card, eventBus)
            is CardCodeBlock -> renderRichText(segment)
            is Blockquote -> renderRichText(segment)
            is CardDiagram -> renderSegment(segment, card)
            is IgnoreMe -> renderSegment() to {}
            else -> throw Exception("No handler for $segment")
        }
    }

    fun FlowContent.renderSegment(canvasName: CanvasName, segment: ListGroup, card: Card, eventBus: EventBus) {

        var index = 0

        fun renderListItems(group: ListGroup) {
            for (listMember in group.members) {
                if (listMember is CardListItem) {
                    renderSegment(canvasName, listMember, card, index++, eventBus)
                } else if (listMember is ListGroup) {
                    // recursive
                    renderSegment(canvasName, listMember, card, eventBus)
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

    fun FlowContent.renderSegment(canvasName: CanvasName, segment: CardListItem, card: Card, index: Int, eventBus: EventBus) {
        div("tdz-task") {
            ensureTaskStyles()
            css {
                cursor = Cursor.pointer
                // minimal vertical padding for density; the hover highlight still fills the full-width row
                padding = Padding(vertical = 1.px, horizontal = 6.px)
                paddingLeft = (6 + segment.level * 24).px
                borderRadius = 4.px
            }

            label("relative cursor-pointer") {
                input(classes="absolute opacity-0 cursor-pointer") {
                    type = InputType.checkBox
                    value = segment.done.toString()
                }

                span("inline-block pr-2") {
                    css {
                        fontWeight = FontWeight.bold
                        if (segment.done) color = ThemeManager.current.checkDone
                    }
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
                    renderRichText(it, card.metadata.columnId, eventBus)
                }
            }

            onClick { event ->
                eventBus.fire(TaskToggleDone(event, canvasName, card, segment))
                event.stopPropagation()
                event.preventDefault()
            }
        }
    }

    /**
     * Inject/refresh the h1 rule from the theme via a typed [styleSheet] (a rule, not inline css{},
     * because it targets a class so every h1 picks it up). Refreshed so a theme switch updates it. The
     * gradient-clip variant uses the typed vendor-prefix extensions (backgroundClipText etc.).
     */
    private fun ensureHeadingStyles() {
        val theme = ThemeManager.current
        styleSheet("tdz-heading-styles") {
            rule(".tdz-h1") {
                fontFamily = theme.headingFont
                fontWeight = FontWeight("${theme.heading1FontWeight}")
                lineHeight = LineHeight("1.05")
                margin = Margin(0.px)
                paddingTop = 0.1.em
                paddingBottom = theme.heading1PadBottom
                letterSpacing = theme.headingLetterSpacing
                // DOS renders section labels in ALL CAPS (group-box / text-UI convention), with wider
                // tracking and a slightly larger size so the tight bitmap caps stay legible.
                if (theme.titleInBorder) {
                    textTransform = TextTransform.uppercase
                    letterSpacing = 0.18.em
                    fontSize = 1.2.em
                } else {
                    textTransform = TextTransform.valueOf(theme.headingTransform)
                    fontSize = theme.heading1FontSize
                }
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
                    textShadow += TextShadow(Color("rgba(0,0,0,0.35)"), 0.px, 1.px, 1.px)
                }
            }
        }
    }

    /** Inject/refresh card-level rules that need a class/pseudo-element selector (so inline css{} can't
     *  carry them): line-height, the optional Material elevation + :hover lift, the ornamental ::before
     *  footer divider, and the Material header strip. */
    private fun ensureCardStyles() {
        val t = ThemeManager.current
        styleSheet("tdz-card-styles") {
            rule(".tdz-card") { lineHeight = LineHeight(t.lineHeight) }
            // Soft/blurred elevation (Material) — css{} only does a single hard offset, so it's a rule.
            // A transition + optional deeper hover shadow give the Material "lift".
            if (t.cardElevation.isNotEmpty()) {
                rule(".tdz-card-elev") {
                    t.cardElevation.forEach { boxShadow += it }
                    transition += Transition("box-shadow", 0.18.s, Timing.ease)
                }
                if (t.cardElevationHover.isNotEmpty()) {
                    rule(".tdz-card-elev:hover") { t.cardElevationHover.forEach { boxShadow += it } }
                }
            }
            // Elegant divider: a short centered line with a centered diamond (the ::before), sized to
            // ~14% of the card and tinted via the muted icon colour.
            if (t.cardFooterFlourish) {
                rule(".tdz-card-flourish") {
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
                rule(".tdz-card-flourish::before") {
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
            // Material header strip: a full-bleed coloured band carrying the card title, with the body
            // padded back in (the card itself zeroes its padding when a strip is present).
            if (t.titleInStrip) {
                rule(".tdz-card-header") {
                    backgroundColor = t.cardHeaderBg
                    color = t.cardHeaderFg
                    fontFamily = t.headingFont
                    fontWeight = FontWeight("500")
                    fontSize = 1.05.rem
                    padding = Padding(vertical = 12.px, horizontal = 16.px)
                }
                rule(".tdz-card-body") { padding = Padding(t.cardPadding) }
            }
        }
    }

    /** Inject/refresh canvas-link chip styling — no underline, and a hover (brighten + arcade shadow). */
    private fun ensureLinkStyles() {
        val t = ThemeManager.current
        styleSheet("tdz-link-styles") {
            rule(".tdz-canvas-link") { textDecoration = TextDecoration.none }
            rule(".tdz-canvas-link:hover") {
                important("background-color", t.linkHoverBg)   // must beat the chip's base fill
                boxShadow += BoxShadow(t.cardShadow, 2.px, 2.px, 0.px)   // hard arcade offset
            }
        }
    }

    /** Inject/refresh the task-row hover highlight — a large hover target over checkbox rows. */
    private fun ensureTaskStyles() {
        styleSheet("tdz-task-styles") {
            rule(".tdz-task:hover") { backgroundColor = ThemeManager.current.taskHover }
        }
    }

    private fun FlowContent.renderSegment(segment: CardHeading) {
        when (segment.level) {
            1 -> {
                ensureHeadingStyles()
                h1("tdz-h1") {
                    +segment.text
                }
            }

            2 -> h2 {
                css {
                    val t = ThemeManager.current
                    fontFamily = t.headingFont
                    fontSize = t.heading2FontSize     // 1em in a text-grid theme; larger elsewhere
                    fontWeight = FontWeight(t.heading2FontWeight.toString())  // ignored by single-weight bitmap fonts (DOS)
                    if (t.headingTransform == "lowercase") textTransform = TextTransform.lowercase
                    color = t.heading2
                    paddingBottom = 4.px
                }
                +segment.text
            }

            3 -> h3("text-lg bold pb-2") {
                +segment.text
            }

            4 -> h4("text-lg pb-2") {
                +segment.text
            }

            else -> p {
                +segment.text
            }
        }
    }

    private fun FlowContent.renderSegment(segment: CardText, card: Card, eventBus: EventBus) {
        div {
            segment.text.forEach { text ->
                renderRichText(text, card.metadata.columnId, eventBus)
            }
        }
    }

    private fun FlowContent.renderSegment(segment: Callout, card: Card, eventBus: EventBus) {
        div("tdz-callout") {
            css {
                padding = Padding(vertical = 1.px, horizontal = 6.px)
                paddingLeft = (6 + segment.level * 24).px
            }
            val ascii = ThemeManager.current.markerStyle == MarkerStyle.ASCII
            span("inline-block pr-2") {
                css { fontWeight = FontWeight.bold }
                +(if (ascii) ">" else when (segment.weight) {
                    1 -> "▷"
                    2 -> "▶"
                    3 -> "➜"
                    else -> "☛"
                })
            }
            segment.text.forEach { renderRichText(it, card.metadata.columnId, eventBus) }
        }
    }

    private fun FlowContent.renderSegment(segment: CardDiagram, card: Card) {
        val t = ThemeManager.current
        // Derive nomnoml's colours from the theme so lines/arrowheads/text stay legible in every theme.
        // Previously these were hardcoded (stroke white, fill black) — fine on dark themes, but the white
        // strokes/arrows were invisible against a light card. stroke = text colour, fill = card background.
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
                try {
                    draw(canvasRef.element, noml)
                } catch (e: Throwable) {
                    console.error("nomnoml draw failed", e)
                }
            }
        }
    }

    // empty segment
    private fun FlowContent.renderSegment() {
        p {}
    }

    private fun FlowContent.renderRichText(value: RichText, columnId: Int, eventBus: EventBus) {
        // TODO this is silly
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
                backgroundColor = Color("rgba(127,127,127,0.08)")
                borderRadius = 3.px
                whiteSpace = WhiteSpace.preWrap
                fontFamily = "monospace"
            }
            button {
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
                    kotlinx.browser.window.navigator.asDynamic().clipboard.writeText(blockquote.text)
                    Unit
                }
            }
            +blockquote.text
        }
    }

    private fun FlowContent.renderRichText(linkText: LinkText, eventBus: EventBus) {
        // TODO this is silly
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
                try {
                    val map = Leaflet.map(mapRef.element)
                    Leaflet.tileLayer(
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
                } catch (e: Throwable) {
                    // Map failed to initialize (e.g. tile/network issue); leave the container empty
                    // rather than breaking the rest of the card.
                }
            }
        }
    }

    private fun FlowContent.renderRichText(text: Text) {
        span {
            +text.text
        }
    }

    private fun FlowContent.renderRichText(link: CanvasLink, eventBus: EventBus) {
        ensureLinkStyles()
        // TODO if the whole card is a link, it should be clickable
        val linkId = "canvas-link-${link.canvasName.normalized()}"

        span("canvas-drop-zone inline-block") {
            attributes["data-canvas-name"] = link.canvasName.normalized()
            id = linkId

            // Fancy chip: bordered pill in the link colour with a nav arrow; hover brightens + arcade shadow.
            a("tdz-canvas-link cursor-pointer") {
                css {
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

            // Set up as drop zone after DOM insertion
            onMount {
                // Get current canvas from App to pass to setupCanvasLinkDropZone
                App.index.resource.canvas?.let { canvas ->
                    tdzDragDrop.setupCanvasLinkDropZone(linkId, canvas.name)
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
            // canvas-link chip): theme link colour, underline, pointer cursor.
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
