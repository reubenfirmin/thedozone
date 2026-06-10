package tdz.view

import kotlinx.css.*
import kotlinx.css.properties.*
import kotlinx.html.ButtonType
import kotlinx.html.button
import kotlinx.html.div
import tdz.view.theme.ThemeManager
import web.html.HTMLElement
import zoned.framework.dom.Ref
import zoned.framework.dom.onClick
import zoned.framework.dom.ref
import zoned.framework.interop.addToBody
import zoned.framework.interop.styleSheet
import zoned.framework.routing.Zone.Companion.zone

/**
 * STUB. The "examples" wizard opened from the empty-root welcome card's "See examples" button
 * ([IntroCard]). For now it is just a placeholder shell — title + coming-soon body + Close — so the
 * affordance is wired end-to-end; the actual example content and flow are TODO (to be designed later).
 *
 * Self-contained body-level zone ([Zones.EXAMPLES_WIZARD]) built with the typed DSL, mirroring the
 * Overlay/ContextMenu rendering model.
 */
object ExamplesWizard {

    private val ID get() = Zones.EXAMPLES_WIZARD.elementId

    fun show() {
        ensureStyles()
        if (Zones.EXAMPLES_WIZARD.resolve() != null) return
        val scrim = Ref<HTMLElement>()
        addToBody {
            div {
                zone(Zones.EXAMPLES_WIZARD)
                ref(scrim)
                // Close on a click on the scrim itself, or on the Close button.
                onClick { e -> if (e.target == scrim.element) hide() }
                div("tdz-wiz-dialog") {
                    div("tdz-wiz-h") { +"Examples" }
                    div("tdz-wiz-body") { +"Example boards are coming soon — they'll give you a ready-made starting point." }
                    button(classes = "tdz-wiz-close", type = ButtonType.button) {
                        +"Close"
                        onClick { hide() }
                    }
                }
            }
        }
    }

    fun hide() { Zones.EXAMPLES_WIZARD.resolve()?.remove() }

    private fun ensureStyles() {
        val t = ThemeManager.current
        styleSheet("$ID-styles") {
            rule("#$ID") {
                position = Position.fixed
                top = 0.px; left = 0.px; right = 0.px; bottom = 0.px
                zIndex = ZLayer.EXAMPLES_WIZARD
                display = Display.flex
                alignItems = Align.center
                justifyContent = JustifyContent.center
                backgroundColor = t.scrimBg
                fontFamily = t.fontFamily
            }
            rule("#$ID .tdz-wiz-dialog") {
                minWidth = 360.px
                maxWidth = 460.px
                backgroundColor = t.menuBg
                color = t.menuFg
                border = Border(1.px, BorderStyle.solid, t.menuBorder)
                borderRadius = 8.px
                padding = Padding(vertical = 24.px, horizontal = 28.px)
            }
            rule("#$ID .tdz-wiz-h") {
                fontSize = 20.px
                fontWeight = FontWeight.w600
                marginBottom = 12.px
            }
            rule("#$ID .tdz-wiz-body") {
                fontSize = 14.px
                lineHeight = LineHeight("1.55")
                opacity = 0.92
                marginBottom = 20.px
            }
            rule("#$ID .tdz-wiz-close") {
                cursor = Cursor.pointer
                fontSize = 13.px
                fontWeight = FontWeight.w600
                backgroundColor = t.link
                color = t.menuBg
                borderStyle = BorderStyle.none
                borderRadius = 6.px
                padding = Padding(vertical = 8.px, horizontal = 16.px)
            }
            rule("#$ID .tdz-wiz-close:hover") {
                put("filter", "brightness(1.08)")
            }
        }
    }
}
