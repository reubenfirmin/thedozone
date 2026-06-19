package tdz.view

/**
 * Minimal external surface of the `vanilla-picker` npm package (a real, self-contained color picker:
 * saturation/value area, hue + alpha sliders, hex/rgba editor — no framework). It is a DEFAULT
 * export, so `@JsModule` sits on the class itself. The build self-injects its CSS.
 *
 * Options object (build with `kotlin.js.json(...)`): `parent` (host element), `popup` (false =
 * inline), `color`, `alpha`, `editor`. Callbacks: `onChange(color)` / `onDone(color)` where `color`
 * exposes `.hex` ("#rrggbbaa"), `.rgbaString`, etc.
 */
@JsModule("vanilla-picker")
@JsNonModule
external class Picker(options: dynamic) {
    var onChange: ((color: dynamic) -> Unit)?
    var onDone: ((color: dynamic) -> Unit)?
    fun setColor(color: String, silent: Boolean = definedExternally)
    fun destroy()
}
