package tdz.view

import web.html.HTMLElement

/**
 * Minimal external surface of the `vanilla-picker` npm package (a real, self-contained color picker:
 * saturation/value area, hue + alpha sliders, hex/rgba editor — no framework). It is a DEFAULT
 * export, so `@JsModule` sits on the class itself. The build self-injects its CSS.
 */
@JsModule("vanilla-picker")
@JsNonModule
external class Picker(options: PickerOptions) {
    var onChange: ((color: PickerColor) -> Unit)?
    var onDone: ((color: PickerColor) -> Unit)?
    fun setColor(color: String, silent: Boolean = definedExternally)
    fun destroy()
}

/**
 * Picker constructor options. [parent] is the host element; [popup] is `false` for an inline picker
 * or an edge string (e.g. `"bottom"`) for a popup; [color] is the initial value; [alpha]/[editor]
 * toggle the alpha slider and the hex/rgba text editor.
 */
external interface PickerOptions {
    var parent: HTMLElement
    var popup: Any?       // false (inline) | "top"/"bottom"/"left"/"right" (popup edge)
    var color: String
    var alpha: Boolean
    var editor: Boolean
}

/** A color reported to the change/done callbacks. [hex] is `#rrggbbaa`; [rgbaString] is `rgba(...)`. */
external interface PickerColor {
    val hex: String
    val rgbaString: String
}

/** Build a typed [PickerOptions] (a plain JS object literal cast to the external shape). */
fun pickerOptions(block: PickerOptions.() -> Unit): PickerOptions =
    (js("({})").unsafeCast<PickerOptions>()).apply(block)
