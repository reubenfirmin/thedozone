package tdz.view

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
    private const val SANS = "ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"

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
        val bg = t.menuBg.toString()
        val fg = t.menuFg.toString()
        val border = t.menuBorder.toString()
        val link = t.link.toString()
        styleSheet("$ID-styles") {
            raw("""
                #$ID { position: fixed; inset: 0; z-index: 10050; display: flex; align-items: center;
                       justify-content: center; background: rgba(0,0,0,0.55); font-family: $SANS; }
                #$ID .tdz-wiz-dialog { min-width: 360px; max-width: 460px; background: $bg; color: $fg;
                       border: 1px solid $border; border-radius: 8px; padding: 24px 28px; }
                #$ID .tdz-wiz-h { font-size: 20px; font-weight: 600; margin-bottom: 12px; }
                #$ID .tdz-wiz-body { font-size: 14px; line-height: 1.55; opacity: 0.92; margin-bottom: 20px; }
                #$ID .tdz-wiz-close { cursor: pointer; font-size: 13px; font-weight: 600; background: $link;
                       color: $bg; border: none; border-radius: 6px; padding: 8px 16px; }
                #$ID .tdz-wiz-close:hover { filter: brightness(1.08); }
            """.trimIndent())
        }
    }
}
