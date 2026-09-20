package tdz.view

import kotlinx.css.*
import kotlinx.html.*
import tdz.model.card.MapLink
import web.html.HTMLDivElement
import zoned.framework.dom.Ref
import zoned.framework.dom.onMount
import zoned.framework.dom.ref
import zoned.framework.interop.css
import zoned.framework.libs.TileLayerOptions
import zoned.framework.libs.loadLeaflet

/**
 * The Leaflet map integration extracted from [CardRenderer]. A [MapLink] renders an OpenStreetMap
 * tile map into a sized container; Leaflet (and its CSS) are code-split and fetched on the first
 * card that actually has a map. Failure degrades gracefully — the container is left empty rather
 * than breaking the rest of the card.
 */
object CardMap {

    /** Default map viewport height (px) when the link doesn't specify one. */
    private const val MAP_DEFAULT_HEIGHT = 200
    /** Highest zoom level the OSM tile layer serves. */
    private const val MAP_MAX_ZOOM = 19
    /** Default map zoom when the link doesn't specify one. */
    private const val MAP_DEFAULT_ZOOM = 12

    fun FlowContent.renderMap(link: MapLink) {
        val mapRef = Ref<HTMLDivElement>()
        div {
            ref(mapRef)
            css {
                width = 100.pct
                height = (link.height ?: MAP_DEFAULT_HEIGHT).px
            }
            onMount {
                // Leaflet (and its CSS) are code-split: fetched on the first card with a map.
                loadLeaflet().then { leaflet ->
                    if (!mapRef.element.isConnected) return@then   // card re-rendered while loading
                    val map = leaflet.map(mapRef.element)
                    leaflet.tileLayer(
                        "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
                        object : TileLayerOptions {
                            override var attribution: String? =
                                "&copy; <a href=\"https://osm.org/copyright\">OpenStreetMap</a> contributors"
                            override var maxZoom: Int? = MAP_MAX_ZOOM
                            override var minZoom: Int? = null
                        },
                    ).addTo(map)
                    map.setView(arrayOf(link.lat, link.long), link.zoom ?: MAP_DEFAULT_ZOOM)
                    map.invalidateSize()
                }.catch {
                    // Map failed to initialize (e.g. chunk/tile/network issue); leave the container
                    // empty rather than breaking the rest of the card.
                }
            }
        }
    }
}
