package tdz.view

import web.events.EventType
import web.keyboard.KeyboardEvent
import web.keyboard.KeyboardEventInit
import zoned.framework.libs.CodeMirrorWrapper

internal fun CodeMirrorWrapper.pressKey(key: String, shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false): KeyboardEvent {
    val event = KeyboardEvent(EventType("keydown"), KeyboardEventInit(
        key = key, shiftKey = shift, ctrlKey = ctrl, altKey = alt, bubbles = true, cancelable = true,
    ))
    view.contentDOM.dispatchEvent(event)
    return event
}
