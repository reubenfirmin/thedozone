package tdz.view.theme

import kotlinx.css.Color
import kotlinx.css.properties.BoxShadow
import kotlinx.css.LinearDimension
import kotlinx.css.em
import kotlinx.css.px
import kotlinx.css.rem

/** List/checkbox marker glyph set (data — chosen per theme, not branched on theme identity). */
enum class MarkerStyle { UNICODE, ASCII }

/**
 * A UI theme: typed [Color] tokens (the single source of truth for every UI colour) plus the Ace
 * [editorTheme]. Components consume tokens directly via typed `css { }`
 * (e.g. `borderColor = ThemeManager.current.cardBorder`). The few unavoidable string sinks
 * (inline `element.style`, injected `<style>` rules, the SVG-data-URI watermark, gradients)
 * interpolate `token.toString()` — the source stays typed.
 */
data class Theme(
    val name: String,
    val editorTheme: String,
    val fontFamily: String,        // app-wide font (DOS uses a monospace bitmap-style font)
    val borderStyle: String,       // CSS border-style: "solid", or "double" for ANSI framing
    val borderWidth: LinearDimension,
    // typography & behaviour as DATA (renderers read these; no branching on theme identity)
    val baseFontSize: LinearDimension,   // one app-wide base size
    val lineHeight: String,              // CSS line-height (kotlin-css has no resolvable LineHeight type)
    val headingFont: String,             // h1/h2 font-family (a theme can give its headings their own identity)
    val heading1FontSize: LinearDimension,
    val heading2FontSize: LinearDimension,
    val headingLetterSpacing: LinearDimension,   // h1 tracking (display faces often want a touch extra)
    val heading1PadBottom: LinearDimension,      // breathing room under the h1
    // Heading weight/case/stroke as DATA (Metro wants light, lowercase, stroke-less type). Defaults
    // reproduce the prior hardcoded h1 (900, no transform, hairline stroke) so existing themes are unchanged.
    val heading1FontWeight: Int = 900,
    val heading2FontWeight: Int = 700,           // FontWeight.bold
    val headingTransform: String = "none",       // CSS text-transform applied to h1 (e.g. "lowercase")
    val headingStrokeWidth: LinearDimension = 0.4.px,   // hairline text-stroke on a monochrome h1 (0 = none)
    val markerStyle: MarkerStyle,        // list/checkbox glyph set
    val titleInBorder: Boolean,          // render a card's leading heading into its top border (group-box)
    // Material: render a card's leading heading into a full-bleed coloured header strip at the top.
    val titleInStrip: Boolean = false,
    val cardHeaderBg: Color = Color("transparent"),   // colour of that header strip
    val cardHeaderFg: Color = Color("transparent"),   // title colour inside the strip
    // app
    val appBg: Color,
    val appGradientInner: Color,
    val appGradientOuter: Color,
    val appBgImage: String,        // full CSS background-image (gradient / dither / rainbow)
    val fg: Color,
    // columns
    val layoutBorder: Color,
    val columnBorder: Color,
    val columnFocusBorder: Color,
    // Optional decorative image (data-URI or url) pinned to the focused/hovered column's top-right
    // corner — a bold focus indicator for themes whose border highlight is too subtle to read.
    // Empty = none (the default for every theme but Rainbow Gaps, which uses the Nyan sprite).
    val columnFocusSprite: String = "",
    // When true, the focused/hovered column shows a bold top accent stripe (Material-style) instead
    // of recolouring its whole frame. Painted as an inset top shadow so layout doesn't shift.
    val focusTopStripe: Boolean = false,
    val panelBg: Color,            // solid/translucent window fill (transparent = none)
    // Optional "smoked glass" backdrop blur on the column panel (CSS blur length, e.g. "6px"): the
    // canvas background showing through a translucent panelBg is frosted. Empty = none (the default).
    val panelBackdropBlur: String = "",
    // Optional box-shadow layers on the column itself, so it reads as a raised Material "sheet" surface
    // that cards then stack on top of (background → sheet → cards). Empty = no column shadow.
    val columnShadow: List<BoxShadow> = emptyList(),
    val columnRadius: LinearDimension,   // rounded "terminal" corners (0 = square)
    val columnGap: LinearDimension,      // gap around each column so the background shows between them
    // Ordered set of solid column fills cycled through by leaf-column index (Metro live-tile grid).
    // Empty = every column uses panelBg (the single-surface behaviour of every other theme). A per-column
    // colour override still wins, since it's painted after render in onMount.
    val columnPalette: List<Color> = emptyList(),
    // cards
    val cardBorder: Color,
    val cardBg: Color,             // card fill (transparent = cards blend into the column/panel)
    val cardShadow: Color,
    val cardRadius: LinearDimension,
    val cardMargin: LinearDimension,   // gap around each card (DOS panels sit tight/flush)
    val cardPadding: LinearDimension,
    // If non-empty, box-shadow layers used for cards instead of the default hard 2D offset — e.g. a
    // soft, blurred Material elevation. Empty = the hard arcade offset (built from cardShadow).
    val cardElevation: List<BoxShadow> = emptyList(),
    // Optional deeper elevation applied on card hover (Material lift). Empty = no hover change.
    val cardElevationHover: List<BoxShadow> = emptyList(),
    // Render a centered ornamental divider at the bottom of content cards (not h1-only title cards).
    val cardFooterFlourish: Boolean = false,
    val heading1: Color,           // monochrome h1 colour (used when headingGradient is empty)
    val headingGlow: Color,        // soft same-hue glow behind a monochrome h1 (transparent = none)
    val headingGradient: String,   // if non-empty, h1 is this CSS gradient clipped to the text instead
    val heading2: Color,
    val link: Color,
    val linkBg: Color,
    val linkHoverBg: Color,
    val checkDone: Color,
    // context menu
    val menuBg: Color,
    val menuBorder: Color,
    val menuFg: Color,
    val menuTitle: Color,
    val menuDisabled: Color,
    val menuHover: Color,
    // drag & drop
    val dragPlaceholder: Color,
    val dragPlaceholderImage: String,   // CSS background-image for the drop preview ("none" = solid colour)
    val dragMirrorBg: Color,
    // misc
    val shadow: Color,
    val icon: Color,
    val iconFaint: Color,
    val watermark: Color,
    val taskHover: Color,
)

/** Default theme: "Blue Room" — dark navy, orange/gold headings, sky-blue cards. */
val blueRoomTheme = Theme(
    name = "Blue Room",
    editorTheme = "ace/theme/cobalt",
    fontFamily = "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
    borderStyle = "solid",
    borderWidth = 2.px,
    baseFontSize = 16.px,
    lineHeight = "normal",
    heading1FontSize = 2.98.rem,
    heading2FontSize = 1.5.rem,
    markerStyle = MarkerStyle.UNICODE,
    titleInBorder = false,
    headingFont = "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
    appBg = Color("#1a2b3c"),
    appGradientInner = Color("#243b55"),
    appGradientOuter = Color("#141e30"),
    appBgImage = "radial-gradient(circle, #243b55 0%, #141e30 100%)",
    fg = Color.white,
    layoutBorder = Color("#1e3a8a"),       // dark blue (match column border — no light stripe)
    columnBorder = Color("#1e3a8a"),       // dark blue
    columnFocusBorder = Color("#3b82f6"),  // mid blue
    panelBg = Color("transparent"),        // Blue Room panels are transparent over the gradient
    columnRadius = 0.px,
    columnGap = 0.px,
    cardBorder = Color("#0ea5e9"),         // sky-500
    cardBg = Color("transparent"),
    cardShadow = Color("#0a0f1a"),         // hard 2D "arcade" offset shadow
    cardRadius = 0.px,                     // square corners in this theme
    cardMargin = 18.px,
    cardPadding = 12.px,
    headingLetterSpacing = (-0.015).em,
    heading1PadBottom = 0.1.em,
    heading1 = Color("#f3f4f6"),           // monochrome (near-white) — artsy via glow/stroke, not hue
    headingGlow = Color("rgba(255,255,255,0.20)"),
    headingGradient = "",                  // monochrome h1
    heading2 = Color("#fbbf24"),           // amber-gold — analogous to the h1 orange (harmonious), clean
    link = Color("#60a5fa"),               // blue-400
    linkBg = Color("rgba(96, 165, 250, 0.14)"),   // canvas-link chip fill
    linkHoverBg = Color("rgba(96, 165, 250, 0.30)"),
    checkDone = Color("#2563eb"),          // blue-600
    menuBg = Color("#243b55"),
    menuBorder = Color("#4b5563"),
    menuFg = Color.white,
    menuTitle = Color("#9ca3af"),
    menuDisabled = Color("#6b7280"),
    menuHover = Color("rgba(255, 255, 255, 0.08)"),
    dragPlaceholder = Color("#3b82f6"),
    dragPlaceholderImage = "none",
    dragMirrorBg = Color("#243b55"),
    shadow = Color("rgba(0, 0, 0, 0.35)"),
    icon = Color.white,
    iconFaint = Color("rgba(255, 255, 255, 0.35)"),
    watermark = Color("rgba(255, 255, 255, 0.12)"),
    taskHover = Color("rgba(255, 255, 255, 0.10)"),  // large hover highlight on task rows
)

/**
 * "DOS" — modeled on DOS-era text UIs: flat dark-gray background, light-gray text, single-line
 * gray borders, and colour used sparingly (yellow titles + cyan accents; black hard shadows).
 */
val dosTheme = Theme(
    name = "DOS",
    editorTheme = "ace/theme/idle_fingers",
    fontFamily = "\"IBM VGA 8x16\", \"Px437 IBM VGA 8x16\", Consolas, \"Courier New\", monospace",
    borderStyle = "solid",              // single-line box; adjacent panels don't stack into 4 lines
    borderWidth = 1.px,                 // crisp 1px rule, like a TUI ─│ frame
    baseFontSize = 19.px,               // VGA font nudged up for readability
    lineHeight = "1.15",                // tight text-grid rows
    heading1FontSize = 1.em,            // one cell size — headings read by colour, not size
    heading2FontSize = 1.em,
    markerStyle = MarkerStyle.ASCII,    // [ ] / [X]
    titleInBorder = true,               // headings sit in the panel's top border (DOS group-box)
    headingFont = "\"IBM VGA 8x16\", \"Px437 IBM VGA 8x16\", Consolas, \"Courier New\", monospace",
    appBg = Color("#1e1e1e"),              // darker hatched desktop, so panels read as windows
    appGradientInner = Color("#1e1e1e"),   // flat — no gradient (TUI uses a dither pattern)
    appGradientOuter = Color("#1e1e1e"),
    appBgImage = "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='4' height='4'%3E%3Crect width='4' height='4' fill='%231e1e1e'/%3E%3Crect width='2' height='2' fill='%23262626'/%3E%3Crect x='2' y='2' width='2' height='2' fill='%23262626'/%3E%3C/svg%3E\")",
    fg = Color("#cfcfcf"),
    layoutBorder = Color("#45c8c8"),       // bright cyan panel frame (Norton-Commander style)
    columnBorder = Color("#45c8c8"),       // bright cyan panel frame — high contrast double-line
    columnFocusBorder = Color("#b6ffff"),  // near-white cyan: clearly distinct focused frame
    panelBg = Color("#2a2a2a"),            // solid window fill (lighter than the dithered desktop)
    columnRadius = 0.px,
    columnGap = 0.px,
    cardBorder = Color("#5f8a8a"),         // dim cyan-grey inner box (subordinate to the panel frame)
    cardBg = Color("transparent"),
    cardShadow = Color("transparent"),     // DOS panels are flat — no floating drop shadow
    cardRadius = 0.px,
    cardMargin = 6.px,                     // tight, near-flush like NC panels
    cardPadding = 6.px,
    headingLetterSpacing = 0.em,
    heading1PadBottom = 0.1.em,
    heading1 = Color("#ffcb2b"),           // bright yellow
    headingGlow = Color("transparent"),    // flat TUI — no glow
    headingGradient = "",                  // monochrome h1
    heading2 = Color("#e8e8e8"),           // bright grey — emphasis without a third hue
    link = Color("#2ce0e0"),               // cyan pop
    linkBg = Color("rgba(44, 224, 224, 0.14)"),
    linkHoverBg = Color("rgba(44, 224, 224, 0.30)"),
    checkDone = Color("#ffffff"),          // bright white tick
    menuBg = Color("#2a2a2a"),
    menuBorder = Color("#555555"),
    menuFg = Color("#cfcfcf"),
    menuTitle = Color("#8a8a8a"),
    menuDisabled = Color("#5a5a5a"),
    menuHover = Color("rgba(255, 255, 255, 0.12)"),
    dragPlaceholder = Color("#2ce0e0"),    // cyan drop target
    dragPlaceholderImage = "none",
    dragMirrorBg = Color("#2a2a2a"),
    shadow = Color("rgba(0, 0, 0, 0.5)"),
    icon = Color("#cfcfcf"),
    iconFaint = Color("rgba(255, 255, 255, 0.30)"),
    watermark = Color("rgba(255, 255, 255, 0.12)"),
    taskHover = Color("rgba(44, 224, 224, 0.14)"),  // cyan selection bar
)

/**
 * "Rainbow Gaps" — translucent, rounded "kitty terminal" panels with gaps over a multi-direction
 * rainbow gradient (hyprland-wallpaper vibe). Modern sans font; bright-grey text, vivid accents.
 */
val rainbowGapsTheme = Theme(
    name = "Rainbow Gaps",
    editorTheme = "ace/theme/tomorrow_night_bright",
    fontFamily = "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
    borderStyle = "solid",
    borderWidth = 1.px,
    baseFontSize = 16.px,
    lineHeight = "normal",
    heading1FontSize = 2.98.rem,
    heading2FontSize = 1.5.rem,
    markerStyle = MarkerStyle.UNICODE,
    titleInBorder = false,
    headingFont = "\"Abril Fatface\", Georgia, serif",   // dramatic display serif — Rainbow Gaps' identity
    appBg = Color("#16161e"),
    appGradientInner = Color("#16161e"),
    appGradientOuter = Color("#16161e"),
    // CONIC rainbow wallpaper, fitted to the reference: centre ~(60%, 75%), sweeping clockwise. Purple
    // anchors the left; clockwise it runs red (top-right) → orange → amber (bottom-right) → green → cyan
    // → blue → back to purple. (So the rightmost vertical reads red→orange→amber, not cyan.)
    appBgImage = "conic-gradient(from 34deg at 50% 86%, " +
        "#ff2d4f 0deg, #ff7a00 30deg, #ffd60a 52deg, #34c759 140deg, #00c7be 175deg, " +
        "#2a7fff 215deg, #8b5cf6 260deg, #ec4899 325deg, #ff2d4f 360deg)",
    fg = Color("#e4e4e7"),                 // bright grey base text
    layoutBorder = Color("transparent"),
    columnBorder = Color("rgba(255,255,255,0.10)"),     // faint terminal edge
    columnFocusBorder = Color("rgba(255,255,255,0.45)"),
    columnFocusSprite = nyanColumnSprite,               // Nyan cat marks the focused column (the faint edge isn't enough)
    panelBg = Color("rgba(18,18,24,0.85)"),             // dark translucent kitty terminal (85%)
    columnRadius = 12.px,                  // rounded terminal corners
    columnGap = 10.px,                     // gaps so the rainbow shows between/around panels
    cardBorder = Color("transparent"),     // clean — the column is the terminal, cards are content
    cardBg = Color("transparent"),
    cardShadow = Color("transparent"),
    cardRadius = 0.px,
    cardMargin = 4.px,
    cardPadding = 8.px,
    headingLetterSpacing = 0.04.em,        // Abril Fatface gets a touch of extra tracking
    heading1PadBottom = 0.4.em,            // more breathing room under the h1
    heading1 = Color("#fafafa"),           // monochrome (near-white) — artsy via glow/stroke, not hue
    headingGlow = Color("rgba(255,255,255,0.25)"),
    headingGradient = "",                  // monochrome h1
    heading2 = Color("#e6e6ea"),           // off-white
    link = Color("#38bdf8"),               // sky
    linkBg = Color("rgba(56,189,248,0.14)"),
    linkHoverBg = Color("rgba(56,189,248,0.30)"),
    checkDone = Color("#34d399"),          // emerald
    menuBg = Color("rgba(24,24,30,0.95)"),
    menuBorder = Color("rgba(255,255,255,0.12)"),
    menuFg = Color("#e4e4e7"),
    menuTitle = Color("#a1a1aa"),
    menuDisabled = Color("#6b7280"),
    menuHover = Color("rgba(255,255,255,0.10)"),
    dragPlaceholder = Color("transparent"),   // drop preview reads as a gap through to the rainbow
    dragPlaceholderImage = "@bg",             // paint the page background (fixed) so it cuts through the column
    dragMirrorBg = Color("rgba(18,18,24,0.95)"),
    shadow = Color("rgba(0,0,0,0.5)"),
    icon = Color("#e4e4e7"),
    iconFaint = Color("rgba(255,255,255,0.35)"),
    watermark = Color("rgba(255,255,255,0.12)"),
    taskHover = Color("rgba(255,255,255,0.08)"),
)

/**
 * "Smooth Dark" — modeled on remotestorage.io (VitePress dark): flat #1b1b1f background, soft #202127
 * rounded cards with gaps, Inter font, warm-grey text + indigo brand accents. Their hero heading is an
 * orange gradient; here it's swapped to a purple gradient.
 */
val smoothDarkTheme = Theme(
    name = "Smooth Dark",
    editorTheme = "ace/theme/one_dark",
    fontFamily = "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
    borderStyle = "solid",
    borderWidth = 1.px,
    baseFontSize = 16.px,
    lineHeight = "normal",
    headingFont = "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
    heading1FontSize = 2.98.rem,
    heading2FontSize = 1.5.rem,
    markerStyle = MarkerStyle.UNICODE,
    titleInBorder = false,
    appBg = Color("#1b1b1f"),              // VitePress dark bg
    appGradientInner = Color("#1b1b1f"),
    appGradientOuter = Color("#1b1b1f"),
    // tight dark-gray → lighter "spotlight" pooled at the top-centre; rich #1b1b1f takes over by the
    // middle and holds to the bottom
    appBgImage = "radial-gradient(110% 90% at 50% -10%, #31313a 0%, #1b1b1f 52%)",
    fg = Color("#dfdfd6"),                 // text-1 (warm off-white)
    layoutBorder = Color("transparent"),
    columnBorder = Color("#26262b"),       // barely-there: a subtle variation of the bg, just discernible
    columnFocusBorder = Color("#a8b1ff"),  // indigo brand
    panelBg = Color("transparent"),        // columns are transparent — cards stand out smoothly
    columnRadius = 12.px,                  // rounded like their feature cards
    columnGap = 16.px,
    cardBorder = Color("transparent"),     // smooth — the fill alone distinguishes cards
    cardBg = Color("#202127"),             // VitePress card fill (cards carry the colour, not the column)
    cardShadow = Color("transparent"),     // smooth — no hard shadow
    cardRadius = 8.px,
    cardMargin = 12.px,
    cardPadding = 16.px,
    headingLetterSpacing = (-0.015).em,
    heading1PadBottom = 0.1.em,
    heading1 = Color("#c084fc"),           // fallback; the gradient below is what renders
    headingGlow = Color("transparent"),
    headingGradient = "linear-gradient(270deg, #c084fc 30%, #7c3aed)",   // purple (their orange, swapped)
    heading2 = Color("#dfdfd6"),
    link = Color("#a8b1ff"),               // indigo brand link
    linkBg = Color("rgba(100,108,255,0.16)"),
    linkHoverBg = Color("rgba(100,108,255,0.30)"),
    checkDone = Color("#42d392"),          // VitePress green
    menuBg = Color("#202127"),
    menuBorder = Color("#3c3f44"),
    menuFg = Color("#dfdfd6"),
    menuTitle = Color("#98989f"),
    menuDisabled = Color("#67676c"),
    menuHover = Color("rgba(255,255,255,0.08)"),
    dragPlaceholder = Color("#2e2e32"),    // subtle border around the preview
    // centre holds the canvas/column bg (not the card); only the very edge eases into a faint warm "burn"
    dragPlaceholderImage = "radial-gradient(100% 100% at 50% 50%, #1b1b1f 0%, #1b1b1f 65%, #141210 100%)",
    dragMirrorBg = Color("#202127"),
    shadow = Color("rgba(0,0,0,0.4)"),
    icon = Color("#dfdfd6"),
    iconFaint = Color("rgba(223,223,214,0.40)"),
    watermark = Color("rgba(255,255,255,0.08)"),
    taskHover = Color("rgba(255,255,255,0.06)"),
)

/**
 * "Hacker" — Matrix-inspired: a black screen with a faint phosphor-green glow, bright green text in a
 * high-quality coding font (JetBrains Mono); headings share Rainbow Gaps' Syne Mono, lit with a green glow.
 */
val hackerTheme = Theme(
    name = "Hacker",
    editorTheme = "ace/theme/gob",                 // green on black
    fontFamily = "\"JetBrains Mono\", ui-monospace, \"SFMono-Regular\", Menlo, Consolas, monospace",
    borderStyle = "solid",
    borderWidth = 1.px,
    baseFontSize = 16.px,
    lineHeight = "normal",
    headingFont = "\"Syne Mono\", ui-monospace, monospace",   // shared with Rainbow Gaps
    heading1FontSize = 2.98.rem,
    heading2FontSize = 1.5.rem,
    markerStyle = MarkerStyle.UNICODE,
    titleInBorder = false,
    appBg = Color("#000400"),
    appGradientInner = Color("#000400"),
    appGradientOuter = Color("#000000"),
    appBgImage = hackerMatrixBg,   // tiled blurry green katakana "matrix rain" under a phosphor vignette
    fg = Color("#43ea66"),                         // phosphor green (readable body)
    layoutBorder = Color("transparent"),
    columnBorder = Color("#0f3a1f"),               // dim green frame
    columnFocusBorder = Color("#00ff66"),          // bright green focus
    panelBg = Color("rgba(1,12,7,0.80)"),          // smoked glass — frosts the matrix rain behind columns
    panelBackdropBlur = "6px",                     // so the crisp glyphs read only in the canvas gaps
    columnRadius = 4.px,
    columnGap = 12.px,
    cardBorder = Color("#155f30"),                 // green card frame
    cardBg = Color("#02110a"),                     // near-black green
    cardShadow = Color("transparent"),
    cardRadius = 4.px,
    cardMargin = 12.px,
    cardPadding = 12.px,
    headingLetterSpacing = 0.em,
    heading1PadBottom = 0.1.em,
    heading1 = Color("#00ff66"),                   // neon green
    headingGlow = Color("rgba(0,255,90,0.55)"),    // Matrix glow
    headingGradient = "",
    heading2 = Color("#00d957"),
    link = Color("#00ffaa"),                       // bright green-cyan
    linkBg = Color("rgba(0,255,140,0.14)"),
    linkHoverBg = Color("rgba(0,255,140,0.30)"),
    checkDone = Color("#00ff41"),                  // classic Matrix green
    menuBg = Color("#020802"),
    menuBorder = Color("#155f30"),
    menuFg = Color("#43ea66"),
    menuTitle = Color("#2a8f4a"),
    menuDisabled = Color("#2f5f3f"),
    menuHover = Color("rgba(0,255,80,0.10)"),
    dragPlaceholder = Color("#00ff66"),
    dragPlaceholderImage = "none",
    dragMirrorBg = Color("#03120a"),
    shadow = Color("rgba(0,40,0,0.6)"),
    icon = Color("#43ea66"),
    iconFaint = Color("rgba(67,234,102,0.40)"),
    watermark = Color("rgba(0,255,80,0.10)"),
    taskHover = Color("rgba(0,255,80,0.08)"),
)

/**
 * "Poodle Rocks" — a light theme modeled on Google Docs/Drive (Material document UI): a soft grey
 * canvas, white cards with thin Material borders and rounded corners, Roboto/Google Sans type, and
 * Google-blue accents with a green "done" tick.
 */
val poodleRocksTheme = Theme(
    name = "Poodle Rocks",
    editorTheme = "ace/theme/chrome",
    fontFamily = "Roboto, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Arial, sans-serif",
    borderStyle = "solid",
    borderWidth = 1.px,
    baseFontSize = 16.px,
    lineHeight = "1.5",
    headingFont = "'Google Sans', 'Product Sans', Roboto, ui-sans-serif, system-ui, Arial, sans-serif",
    heading1FontSize = 2.0.rem,
    heading2FontSize = 1.4.rem,
    headingLetterSpacing = 0.em,
    heading1PadBottom = 0.2.em,
    markerStyle = MarkerStyle.UNICODE,
    titleInBorder = false,
    titleInStrip = true,                   // card titles sit in a Google-blue header strip
    cardHeaderBg = Color("#1a73e8"),
    cardHeaderFg = Color("#ffffff"),
    appBg = Color("#f1f3f4"),              // Google grey canvas
    appGradientInner = Color("#ffffff"),
    appGradientOuter = Color("#f1f3f4"),
    appBgImage = "radial-gradient(120% 90% at 50% -10%, #ffffff 0%, #f1f3f4 60%)",
    fg = Color("#202124"),                 // Google grey 900
    layoutBorder = Color("transparent"),
    columnBorder = Color("transparent"),   // the sheet is defined by elevation, not a stroke
    columnFocusBorder = Color("#1a73e8"),  // Google blue
    focusTopStripe = true,                 // hovered column shows a bold blue top stripe, not a full frame
    // Stacked surfaces: grey canvas (0) → white column "sheet" (1dp) → white cards (2dp). The sheet
    // is a real surface the cards rest on, each level casting its own shadow (Material elevation).
    panelBg = Color("#ffffff"),
    columnShadow = listOf(   // 1dp sheet (Material elevation, 3 layers)
        BoxShadow(Color("rgba(0,0,0,0.14)"), 0.px, 1.px, 1.px, 0.px),
        BoxShadow(Color("rgba(0,0,0,0.12)"), 0.px, 2.px, 1.px, (-1).px),
        BoxShadow(Color("rgba(0,0,0,0.20)"), 0.px, 1.px, 3.px, 0.px),
    ),
    columnRadius = 8.px,
    columnGap = 16.px,
    cardBorder = Color("transparent"),     // pure Material surface — elevation alone, no outline
    cardBg = Color("#ffffff"),             // white "document" cards
    cardShadow = Color("transparent"),     // unused — cardElevation provides Material elevation
    cardRadius = 8.px,
    cardMargin = 14.px,
    cardPadding = 16.px,
    // Authoritative MDC elevation (umbra / penumbra / ambient): rests at 2dp, lifts to 8dp on hover.
    cardElevation = listOf(            // Material 2dp resting elevation (3 layers)
        BoxShadow(Color("rgba(0,0,0,0.14)"), 0.px, 2.px, 2.px, 0.px),
        BoxShadow(Color("rgba(0,0,0,0.12)"), 0.px, 3.px, 1.px, (-2).px),
        BoxShadow(Color("rgba(0,0,0,0.20)"), 0.px, 1.px, 5.px, 0.px),
    ),
    cardElevationHover = listOf(       // deeper 8dp lift on hover
        BoxShadow(Color("rgba(0,0,0,0.14)"), 0.px, 8.px, 10.px, 1.px),
        BoxShadow(Color("rgba(0,0,0,0.12)"), 0.px, 3.px, 14.px, 2.px),
        BoxShadow(Color("rgba(0,0,0,0.20)"), 0.px, 5.px, 5.px, (-3).px),
    ),
    heading1 = Color("#202124"),
    headingGlow = Color("transparent"),
    headingGradient = "",
    heading2 = Color("#3c4043"),           // Google grey 800
    link = Color("#1a73e8"),               // Google blue
    linkBg = Color("rgba(26,115,232,0.10)"),
    linkHoverBg = Color("rgba(26,115,232,0.20)"),
    checkDone = Color("#1e8e3e"),          // Google green
    menuBg = Color("#ffffff"),
    menuBorder = Color("#dadce0"),
    menuFg = Color("#202124"),
    menuTitle = Color("#5f6368"),          // Google grey 700
    menuDisabled = Color("#9aa0a6"),
    menuHover = Color("rgba(60,64,67,0.08)"),
    dragPlaceholder = Color("#e8f0fe"),    // Google blue-50 selection fill
    dragPlaceholderImage = "none",
    dragMirrorBg = Color("#ffffff"),
    shadow = Color("rgba(60,64,67,0.30)"),
    icon = Color("#5f6368"),
    iconFaint = Color("rgba(60,64,67,0.40)"),
    watermark = Color("rgba(60,64,67,0.08)"),
    taskHover = Color("rgba(60,64,67,0.08)"),
)

/**
 * "Immaterial" — a light, reading-first theme borrowing Medium's typography: a serif body at a large
 * size with generous 1.58 line-height on near-white, sans-serif headings, no card chrome (text flows
 * with whitespace), and Medium's green for links/accents.
 */
val immaterialTheme = Theme(
    name = "Immaterial",
    editorTheme = "ace/theme/github",
    fontFamily = "Charter, 'Iowan Old Style', 'Source Serif 4', Georgia, 'Times New Roman', serif",
    borderStyle = "solid",
    borderWidth = 1.px,
    baseFontSize = 19.px,                  // larger base for comfortable reading
    lineHeight = "1.58",                   // Medium's body line-height
    headingFont = "'Sohne', 'Helvetica Neue', Inter, ui-sans-serif, system-ui, Arial, sans-serif",
    heading1FontSize = 2.5.rem,
    heading2FontSize = 1.6.rem,
    headingLetterSpacing = (-0.02).em,     // tight tracking like Medium titles
    heading1PadBottom = 0.3.em,
    markerStyle = MarkerStyle.UNICODE,
    titleInBorder = false,
    appBg = Color("#ffffff"),
    appGradientInner = Color("#ffffff"),
    appGradientOuter = Color("#ffffff"),
    appBgImage = "none",                   // flat white reading surface
    fg = Color("#242424"),                 // Medium near-black body text
    layoutBorder = Color("transparent"),
    columnBorder = Color("#e8e8e8"),       // barely-there separator
    columnFocusBorder = Color("#1a8917"),  // Medium green
    panelBg = Color("transparent"),        // white app surface shows through
    columnRadius = 0.px,
    columnGap = 16.px,
    cardBorder = Color("transparent"),     // no card chrome — text flows with whitespace
    cardBg = Color("transparent"),
    cardShadow = Color("transparent"),
    cardRadius = 0.px,
    cardMargin = 16.px,                    // generous spacing between blocks
    cardPadding = 8.px,
    cardFooterFlourish = true,             // ornamental centered divider at the foot of content cards
    heading1 = Color("#242424"),
    headingGlow = Color("transparent"),
    headingGradient = "",
    heading2 = Color("#242424"),
    link = Color("#1a8917"),               // Medium green
    linkBg = Color("rgba(26,137,23,0.10)"),
    linkHoverBg = Color("rgba(26,137,23,0.18)"),
    checkDone = Color("#1a8917"),
    menuBg = Color("#ffffff"),
    menuBorder = Color("#e6e6e6"),
    menuFg = Color("#242424"),
    menuTitle = Color("#6b6b6b"),
    menuDisabled = Color("#b3b3b3"),
    menuHover = Color("rgba(0,0,0,0.05)"),
    dragPlaceholder = Color("#f2f2f2"),
    dragPlaceholderImage = "none",
    dragMirrorBg = Color("#ffffff"),
    shadow = Color("rgba(0,0,0,0.12)"),
    icon = Color("#6b6b6b"),
    iconFaint = Color("rgba(0,0,0,0.35)"),
    watermark = Color("rgba(0,0,0,0.06)"),
    taskHover = Color("rgba(0,0,0,0.04)"),
)

/**
 * "Metro" — modeled on Windows Phone / Microsoft's Metro design: a near-black canvas almost wholly
 * covered by flush, solid rectangular colour tiles (the columns) cycled from an ordered palette, with
 * flat white type — no gaps, borders, rounded corners, gradients, or shadows. Headings take the Metro
 * voice: large, light-weight (300), lowercase Segoe UI.
 */
val metroTheme = Theme(
    name = "Metro",
    editorTheme = "ace/theme/clouds_midnight",
    // Segoe UI is the authentic Microsoft face (present on Windows); graceful fallback elsewhere.
    fontFamily = "\"Segoe UI\", \"Segoe UI Web (West European)\", system-ui, -apple-system, \"Helvetica Neue\", Arial, sans-serif",
    borderStyle = "solid",
    borderWidth = 0.px,                    // tiles sit flush; no frame
    baseFontSize = 16.px,
    lineHeight = "1.45",
    headingFont = "\"Segoe UI\", \"Segoe UI Web (West European)\", system-ui, -apple-system, \"Helvetica Neue\", Arial, sans-serif",
    heading1FontSize = 3.1.rem,            // large Metro header
    heading2FontSize = 1.5.rem,
    heading1FontWeight = 300,              // Segoe UI Light — the thin Metro weight
    heading2FontWeight = 400,
    headingTransform = "lowercase",        // Metro headers are set lowercase
    headingStrokeWidth = 0.px,             // flat — no hairline stroke fattening the thin type
    headingLetterSpacing = (-0.01).em,     // slight tightening on the light display weight
    heading1PadBottom = 0.2.em,
    markerStyle = MarkerStyle.UNICODE,
    titleInBorder = false,
    appBg = Color("#000000"),              // black canvas; tiles cover almost all of it
    appGradientInner = Color("#000000"),
    appGradientOuter = Color("#000000"),
    appBgImage = "none",                   // flat — Metro is gradient-free
    fg = Color("#ffffff"),                 // white type on the saturated tiles
    layoutBorder = Color("transparent"),
    columnBorder = Color("transparent"),   // adjacent tiles touch directly — no seam
    columnFocusBorder = Color("rgba(255,255,255,0.85)"),   // crisp white inset on the focused tile
    // borderWidth is 0 so tiles stay flush (a transparent border would open black seams); show focus
    // as an inset top stripe (box-shadow, no layout shift) instead of recolouring an invisible border.
    focusTopStripe = true,
    panelBg = Color("#1f1f1f"),            // fallback only; columnPalette supplies the real fills
    // Ordered Windows-Phone accent tiles; columns cycle through these by index. All carry white text.
    columnPalette = listOf(
        Color("#00ABA9"),                  // teal
        Color("#0050EF"),                  // cobalt
        Color("#D80073"),                  // magenta
        Color("#6A00FF"),                  // violet
        Color("#FA6800"),                  // orange
        Color("#008A00"),                  // emerald
        Color("#A20025"),                  // crimson
        Color("#647687"),                  // steel
    ),
    columnRadius = 0.px,                   // square tiles
    columnGap = 0.px,                      // flush — no gaps between tiles
    cardBorder = Color("transparent"),     // cards blend into their tile
    cardBg = Color("transparent"),
    cardShadow = Color("transparent"),     // flat
    cardRadius = 0.px,
    cardMargin = 6.px,                     // tight blocks within the tile
    cardPadding = 12.px,
    heading1 = Color("#ffffff"),
    headingGlow = Color("transparent"),    // flat — no glow
    headingGradient = "",
    heading2 = Color("#ffffff"),
    link = Color("#ffffff"),               // white, underlined-by-chip; the tile already carries the hue
    linkBg = Color("rgba(255,255,255,0.18)"),
    linkHoverBg = Color("rgba(255,255,255,0.34)"),
    checkDone = Color("#ffffff"),
    menuBg = Color("#1f1f1f"),             // flat dark Metro flyout
    menuBorder = Color("#2d89ef"),         // cobalt edge
    menuFg = Color("#ffffff"),
    menuTitle = Color("#bdbdbd"),
    menuDisabled = Color("#6d6d6d"),
    menuHover = Color("rgba(255,255,255,0.14)"),
    dragPlaceholder = Color("rgba(255,255,255,0.25)"),
    dragPlaceholderImage = "none",
    dragMirrorBg = Color("#1f1f1f"),
    shadow = Color("rgba(0,0,0,0.5)"),
    icon = Color("#ffffff"),
    iconFaint = Color("rgba(255,255,255,0.45)"),
    watermark = Color("rgba(255,255,255,0.12)"),
    taskHover = Color("rgba(255,255,255,0.12)"),
)

/** All selectable themes, in menu order. */
val themes = listOf(blueRoomTheme, dosTheme, rainbowGapsTheme, smoothDarkTheme, hackerTheme,
    poodleRocksTheme, immaterialTheme, metroTheme)

/** Holds the active theme. Switching applies on the next render (callers trigger a re-render). */
object ThemeManager {
    var current: Theme = blueRoomTheme
        private set

    fun use(theme: Theme) {
        current = theme
    }
}
