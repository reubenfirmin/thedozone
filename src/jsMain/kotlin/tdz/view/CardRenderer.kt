package tdz.view

import kotlinx.css.Color
import kotlinx.html.*
import tdz.App
import tdz.controller.events.EventBus
import tdz.controller.events.TaskToggleDone
import tdz.model.*
import tdz.model.card.*
import web.dom.document
import web.html.HTMLCanvasElement
import web.window.window
import zoned.framework.dom.onClick
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

    fun FlowContent.render(canvasName: CanvasName, cards: List<Card>, eventBus: EventBus) {
        for (card in cards) {
            render(canvasName, card, eventBus)
        }
    }

    private fun FlowContent.render(canvasName: CanvasName, card: Card, eventBus: EventBus) {
        div("border-solid border-2 border-sky-500 rounded-md my-4 p-2") {
            id = "card-${card.metadata.columnId}-${card.index}"
            for (segment in card.segments) {
                renderSegment(canvasName, segment, card, eventBus)
            }
        }
    }

    private fun FlowContent.renderSegment(canvasName: CanvasName, segment: CardSegment, card: Card, eventBus: EventBus) {
        when (segment) {
            is ListGroup -> renderSegment(canvasName, segment, card, eventBus)
            is CardListItem -> throw Exception("Didn't expect ungrouped list item")
            is CardHeading -> renderSegment(segment)
            is CardText -> renderSegment(segment, card)
            is CardCodeBlock -> renderRichText(segment)
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
        div {

            label("relative cursor-pointer") {
                input(classes="absolute opacity-0 cursor-pointer") {
                    type = InputType.checkBox
                    value = segment.done.toString()
                }

                span("inline-block cursor-pointer peer-checked:text-blue-600 pr-2") {
                    if (segment.done) {
                        +"\u2713"
                    } else {
                        if (segment.type == ListMemberType.NUMBERED) {
                            +(circledNumbers[index])
                        } else if (segment.type == ListMemberType.BULLET) {
                            +"\u2610"
                        } else if (segment.type == ListMemberType.ARROW) {
                            +when (segment.weight) {
                                1 -> "\u25B7"
                                2 -> "\u25B6"
                                3 -> "\u279C"
                                else -> "\u261B"
                            }
                        }
                    }
                }

                segment.text.forEach {
                    renderRichText(it, card.metadata.columnId)
                }
            }

            onClick { event ->
                eventBus.fire(TaskToggleDone(event, canvasName, card, segment))
                event.stopPropagation()
                event.preventDefault()
            }
        }
    }

    private fun FlowContent.renderSegment(segment: CardHeading) {
        when (segment.level) {
            1 -> h1("text-2xl py-4") {
                +segment.text
            }

            2 -> h2("text-xl pb-2") {
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

    private fun FlowContent.renderSegment(segment: CardText, card: Card) {
        div {
            segment.text.forEach { text ->
                renderRichText(text, card.metadata.columnId)
            }
        }
    }

    private fun FlowContent.renderSegment(segment: CardDiagram, card: Card) {
        val canvasId = card.tagId() + "_canvas"
        canvas("w-full") {
            id = canvasId

        }

        val diagramFill = Color.black
        val diagramStroke = Color.white

        val noml = listOf(
            "#stroke: $diagramStroke",
            "#fill: $diagramFill",
            segment.text
        )
        try {
            val canvas = document.getElementById(canvasId) as HTMLCanvasElement
            draw(canvas, noml.joinToString("\n"))
        } catch (e: Throwable) {
            // TODO show error somehow
        }
    }

    // empty segment
    private fun FlowContent.renderSegment() {
        p {}
    }

    private fun FlowContent.renderRichText(value: RichText, columnId: Int) {
        // TODO this is silly
        when (value) {
            is CardCodeBlock -> renderRichText(value)
            is LinkText -> renderRichText(value)
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

    private fun FlowContent.renderRichText(linkText: LinkText) {
        // TODO this is silly
        when (linkText) {
            is CanvasLink -> renderRichText(linkText)
            is ExternalLink -> renderRichText(linkText)
            is ImageLink -> renderRichText(linkText)
        }
    }

    private fun FlowContent.renderRichText(text: Text) {
        span {
            +text.text
        }
    }

    private fun FlowContent.renderRichText(link: CanvasLink) {
        // TODO if the whole card is a link, it should be clickable
        a {
            +link.label
            href = App.index.canvas.path(link.canvasName.normalized())
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
