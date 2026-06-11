package tdz.view.theme

import web.dom.ElementId
import web.dom.document
import zoned.framework.interop.StyleSheetScope
import zoned.framework.interop.styleSheet

/**
 * [styleSheet] guarded by theme identity. Rewriting an unchanged `<style>` tag still forces a
 * global style recalc, and the hot paths call their ensure*() injections on every render pass
 * (cards) or pointer event (wormhole portal). Each id is (re)written only when the active theme is
 * not the one that last produced it — or its tag is gone from the head.
 *
 * Only for blocks that are pure functions of [ThemeManager.current]; rules embedding other dynamic
 * state (e.g. a per-column accent color) must keep calling [styleSheet] directly.
 */
private val appliedTheme = mutableMapOf<String, Theme>()

fun themedStyleSheet(id: String, block: StyleSheetScope.() -> Unit) {
    if (appliedTheme[id] === ThemeManager.current && document.getElementById(ElementId(id)) != null) return
    styleSheet(id, block)
    appliedTheme[id] = ThemeManager.current
}
