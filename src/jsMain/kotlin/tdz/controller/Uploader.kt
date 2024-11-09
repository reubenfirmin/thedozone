package tdz.controller

import tdz.model.*
import tdz.model.card.CanvasLink
import tdz.model.card.CardText
import tdz.model.card.RichText
import tdz.controller.events.EventBus
import tdz.model.localstorage.CanvasDoc
import tdz.model.markdown.MarkdownService

class Uploader(val model: Model,
               val eventBus: EventBus) {

    /**
     * Create a component that will spawn a file upload (file browser) on an UPLOAD_CANVAS event. Component must be
     * added to the dom, but is hidden.
     * @param createNew if true, create a new canvas to upload into
     */
//    fun uploadHiddenElement(id: String, createNew: Boolean): Component {
//        val input = fileUploadInput(id)
//
//        input.addAfterInsertHook {
//            input.getElement()!!.addEventListener("change", { event ->
//                val files = event.target!!.asDynamic().files
//                val file = files[0]
//                val reader = FileReader()
//                reader.onload = { loadEvent ->
//                    readFile(file.name as String, loadEvent.target!!.asDynamic().result as String, createNew)
//                }
//                reader.readAsText(file as Blob)
//            })
//        }
//
//        eventBus.on(input, UPLOAD_CANVAS) { event ->
//            if ((event as UploadCanvas).createNew == createNew) {
//                input.getElement()?.dispatchEvent(MouseEvent("click"))
//            }
//        }
//
//        return input
//    }

    /**
     * @param content uploaded markdown
     * @param createNew true to create a new canvas, false to upload in place
     */
    private fun readFile(name: String, content: String, createNew: Boolean) {

        fun saveCardsToCanvas(cards: List<Card>, canvasName: CanvasName) {
            val groupedCards = cards.groupBy { it.metadata.columnId }
            val canvas = model.getCanvas(canvasName)!!
            val updated = mutableListOf<ColumnId>()
            for (columnId in groupedCards.keys) {
                val useColumn = if (canvas.layoutMap.containsKey(columnId)) {
                    canvas.firstNonLayout()
                } else {
                    columnId
                }
                val existing = model.cards(canvasName, useColumn)
                model.saveCards(canvasName, useColumn, existing + groupedCards[columnId]!!)
                updated.add(useColumn)
            }
//            eventBus.fire(CardsChanged(canvasName, updated, "upload"))
        }

        val line1 = content.split("\n").first()
        val (canvasPrototype, markdown) = if (line1.startsWith("{")) {
            val doc = CanvasDoc.fromJson(JSON.parse(line1))
            Canvas(doc.name, doc.parentName, doc.columns, doc.layouts) to content.removePrefix(line1)
        } else {
            null to content
        }
        val cardsInColumns = MarkdownService.fromMd(markdown)
        val cards = cardsInColumns.flatten()
        val currentCanvas = model.currentCanvas()
        if (createNew) {
            // remove characters that would break the path, and avoid dupes
            val canvasName = sanitize(name)
            // create new canvas, add cards from file to it, create a new card on current canvas pointing at destination
            if (canvasPrototype != null) {
                model.addCanvas(canvasName, currentCanvas.name, canvasPrototype.columns, canvasPrototype.layouts)
            } else {
                model.addCanvas(
                    canvasName, currentCanvas.name, (0..cardsInColumns.size).toList(),
                    listOf(ColumnLayoutSpec(0, Split.VERTICAL, (1..cardsInColumns.size).toList()))
                )
            }
            saveCardsToCanvas(cards, canvasName)

            // create a link to the new canvas
            val existingCards = model.cards(currentCanvas.name, currentCanvas.firstNonLayout())
            val newCard = Card(
                listOf(
                    CardText(listOf<RichText>(CanvasLink(canvasName, canvasName.display())))
                ), CardMetadata(currentCanvas.firstNonLayout()), cards.size
            )
            model.saveCards(currentCanvas.name, currentCanvas.firstNonLayout(), existingCards + listOf(newCard))
//            debug("Created new canvas $canvasName and link", MODEL)
//            eventBus.fire(CardsChanged(currentCanvas.name, listOf(currentCanvas.firstNonLayout()), "upload"))
        } else {
            saveCardsToCanvas(cards, currentCanvas.name)
        }
    }
//
//    private fun fileUploadInput(id: String) = CustomTag("input", className = "fileupload") {
//        setAttribute("id", id)
//        setAttribute("type", "file")
//        setAttribute("accept", ".tdz")
//        setAttribute("hidden", "")
//    }

    private fun sanitize(name: String): CanvasName {
        var baseName = name
        while (model.exists(CanvasName(baseName))) {
            baseName = CanvasName("${baseName}_").normalized()
        }
        return CanvasName(baseName)
    }
}