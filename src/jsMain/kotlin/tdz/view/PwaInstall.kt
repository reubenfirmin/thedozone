package tdz.view

import kotlinx.browser.window

/**
 * Bridges the browser's PWA install flow to the in-app "Install app" menu item.
 *
 * The `beforeinstallprompt` event is captured into `window.__tdzDeferredInstall` by an inline script
 * in index.html — it can fire before this bundle boots, so the capture must live in the page, not
 * here. We adopt that stashed event on demand. A deferred prompt is single-use, so [prompt] clears
 * it after firing. Unavailable where the browser doesn't support in-page installation (e.g. iOS
 * Safari never fires the event) or when the app is already running installed.
 */
object PwaInstall {

    private fun deferred(): dynamic = window.asDynamic().__tdzDeferredInstall

    private fun isStandalone(): Boolean =
        try {
            window.matchMedia("(display-mode: standalone)").matches ||
                window.asDynamic().navigator.standalone == true
        } catch (e: Throwable) {
            false
        }

    /** True when the browser offered an install prompt we can still fire and we're not installed. */
    fun isAvailable(): Boolean = deferred() != null && !isStandalone()

    /** Fire the captured install prompt. Single-use: clears the stashed event afterwards. */
    fun prompt() {
        val event = deferred() ?: return
        try {
            event.prompt()
        } catch (e: Throwable) {
            // already used / detached — nothing to do
        }
        window.asDynamic().__tdzDeferredInstall = null
    }
}
