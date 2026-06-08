package tdz.view.sync

import kotlinx.css.Color
import tdz.view.theme.ThemeManager

/**
 * Colors for the sync UI. Reuses existing theme tokens where possible and defines the few extras
 * locally (this keeps the work inside the sync vertical — no edits to the shared Theme). Single
 * swap point if/when the theme exposes dedicated tokens.
 */
object SyncColors {
    val modalOverlay get() = Color("rgba(0, 0, 0, 0.55)")
    val border get() = ThemeManager.current.menuBorder

    // Primary call-to-action: the theme accent (`link`) as a light fill with dark (menu-bg) text. Every
    // theme here has a dark menu surface and a light/vivid accent, so this is high-contrast AND on-theme.
    // (Previously this reused `columnFocusBorder` — a near-white focus ring — which on some themes is a
    // translucent white, rendering primary buttons as unreadable white-on-lilac.)
    val primaryBg get() = ThemeManager.current.link
    val primaryFg get() = ThemeManager.current.menuBg

    // Secondary / ghost buttons: subtle recessed fill, normal menu text, visible border.
    val buttonBg get() = ThemeManager.current.appBg
    val buttonFg get() = ThemeManager.current.menuFg
    val buttonBorder get() = ThemeManager.current.menuBorder

    // The blocking overlay sits on the dark scrim, so its spinner text must stay light.
    val overlayFg get() = Color.white

    val inputBg get() = ThemeManager.current.appBg
    val inputBorder get() = ThemeManager.current.menuBorder
    val badgeBg get() = ThemeManager.current.menuBg
    val badgeFg get() = ThemeManager.current.menuFg
}
