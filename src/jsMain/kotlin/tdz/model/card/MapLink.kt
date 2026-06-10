package tdz.model.card

data class MapLink(val lat: Double, val long: Double, val zoom: Int?, val height: Int?): LinkText {

    override fun isImage() = false

    override fun isCanvasLink() = false

    override fun toMarkdown(): String {
        val baseString = "map($lat, $long"
        val zoomString = zoom?.let { ", $it" } ?: ""
        val heightString = height?.let { ", $it" } ?: ""
        return "[$baseString$zoomString$heightString)]"
    }
}