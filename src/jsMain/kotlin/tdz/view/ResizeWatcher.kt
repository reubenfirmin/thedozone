package tdz.view

import kotlinx.browser.window

/**
 * Watches the viewport and invokes [onCrossBreakpoint] when it crosses the mobile breakpoint
 * (rotation, responsive resize), so the board can flip between full-layout and single-column.
 * Debounced (a burst of resize events fires the callback once) and edge-triggered (only on an
 * actual mobile↔desktop flip, not on every pixel). Installed once for the app's life.
 */
class ResizeWatcher(private val onCrossBreakpoint: () -> Unit) {

    private var wasMobile = isMobile()
    private var debounce = 0

    fun install() {
        window.addEventListener("resize", {
            window.clearTimeout(debounce)
            debounce = window.setTimeout({
                val nowMobile = isMobile()
                if (nowMobile != wasMobile) {
                    wasMobile = nowMobile
                    onCrossBreakpoint()
                }
            }, 150)
        })
    }

    private fun isMobile() = window.innerWidth <= CanvasColumns.MOBILE_BREAKPOINT
}
