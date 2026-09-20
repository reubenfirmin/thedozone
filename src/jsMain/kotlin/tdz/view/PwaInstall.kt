package tdz.view

import kotlinx.browser.window
import zoned.framework.Debugger

/**
 * The `beforeinstallprompt` event Chrome fires (and the inline script in index.html stashes). Its
 * [prompt] shows the browser's install UI; it can be fired at most once. Typed as far as we use it.
 */
external interface BeforeInstallPromptEvent {
    fun prompt()
}

/** Minimal typed view of the globals the install flow reads: the stashed prompt event and the
 *  legacy iOS `navigator.standalone` flag (Safari-only, absent elsewhere — hence nullable). */
private external interface InstallGlobals {
    var __tdzDeferredInstall: BeforeInstallPromptEvent?
}

private external interface IosNavigator {
    val standalone: Boolean?
}

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

    private val globals: InstallGlobals get() = window.unsafeCast<InstallGlobals>()

    private fun deferred(): BeforeInstallPromptEvent? = globals.__tdzDeferredInstall

    private fun isStandalone(): Boolean =
        window.matchMedia("(display-mode: standalone)").matches ||
            // iOS Safari's non-standard flag — absent (null) on every other browser.
            window.navigator.unsafeCast<IosNavigator>().standalone == true

    /** True when the browser offered an install prompt we can still fire and we're not installed. */
    fun isAvailable(): Boolean = deferred() != null && !isStandalone()

    /** Fire the captured install prompt. Single-use: clears the stashed event afterwards. */
    fun prompt() {
        val event = deferred() ?: return
        try {
            event.prompt()
        } catch (e: Throwable) {
            // Already used / detached — the prompt is single-use, so this is benign; just note it.
            Debugger.debug("PwaInstall.prompt() failed (event likely already consumed): ${e.message}")
        }
        globals.__tdzDeferredInstall = null
    }
}
