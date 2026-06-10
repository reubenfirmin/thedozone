package tdz.view

import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.events.EventBus
import tdz.controller.events.RefreshColumns
import tdz.model.Canvas
import tdz.model.Card
import tdz.model.ColumnId
import tdz.model.Direction
import tdz.model.Model
import tdz.model.Direction.*
import tdz.model.Split.HORIZONTAL
import tdz.model.Split.VERTICAL
import tdz.tdzDragDrop
import tdz.view.Boundary.Companion.calculateBoundary
import tdz.view.theme.ThemeManager
import web.dom.document
import web.window.window
import zoned.framework.Debugger
import zoned.framework.dom.onMount
import zoned.framework.interop.css
import zoned.framework.interop.getHtmlElement
import zoned.framework.interop.rebuildInPlace
import zoned.framework.interop.styleSheet


/** Encapsulated model/view of current columns */
class CanvasColumns(val canvas: Canvas,
                    val eventBus: EventBus,
                    val model: Model,
                    val cards: (columnId: Int) -> List<Card>) {

    private val boundaries = mutableMapOf<ColumnId, Boundary>()
    private val columnModes = mutableMapOf<ColumnId, Mode>()
    private val columnContainers = mutableMapOf<ColumnId, ColumnContainer>()
    private var focusedColumn: ColumnId = canvas.columns.firstOrNull { !canvas.layoutMap.containsKey(it) } ?: canvas.columns.firstOrNull() ?: 0
    private var disposed = false

    // Named (not inline at the subscription site) so dispose() can detach the exact reference.
    private val refreshHandler: (RefreshColumns) -> Unit = { event ->
        if (event.canvasName == canvas.name) {
            refreshColumns(event.columnIds)
        }
    }

    init {
        // Load persisted modes from localStorage
        canvas.columns.forEach { columnId ->
            val persistedMode = loadModeFromStorage(columnId)
            if (persistedMode != null) {
                columnModes[columnId] = persistedMode
            }
        }

        // Listen for refresh events (detached again in [dispose])
        eventBus.on(refreshHandler)
    }

    /**
     * Detach this instance before it is abandoned (each canvas render builds a fresh CanvasColumns):
     * saves/tears down any open editors and removes the [RefreshColumns] subscription. Without this,
     * every re-render leaks a permanent handler that keeps firing against a DOM it no longer owns —
     * and unsaved editor buffers are silently discarded with the old DOM.
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        columnContainers.values.forEach { it.beforeRemoval() }
        eventBus.off(refreshHandler)
    }

    private fun loadModeFromStorage(columnId: ColumnId): Mode? =
        model.getColumnMode(canvas.name, columnId)

    private fun saveModeToStorage(columnId: ColumnId, mode: Mode) {
        model.setColumnMode(canvas.name, columnId, mode)
    }

    fun color(columnId: Int): Color {
        // TODO
        return Color.cyan
    }

    /**
     * The themed background fill for a column. When the theme supplies a [columnPalette] (Metro's
     * live-tile grid) the fill cycles through it by the column's ordinal among leaf (content) columns,
     * so the order is stable and predictable; otherwise every column uses the single `panelBg`. A
     * per-column color override is painted later (onMount) and still wins over this default.
     */
    private fun paletteBg(columnId: Int): Color {
        val t = ThemeManager.current
        if (t.columnPalette.isEmpty()) return t.panelBg
        val leaves = canvas.columns.filter { !canvas.layoutMap.containsKey(it) }
        val idx = leaves.indexOf(columnId).coerceAtLeast(0)
        return t.columnPalette[idx % t.columnPalette.size]
    }

    /**
     * @param container - container to render into
     * @param regenerateColumns - which columns should be recomputed (before re-rendering)
     * @param cardStyle - style to apply to cards
     */
    fun FlowContent.render(regenerateColumns: List<Int>) {
        console.log("CanvasColumns.render called with regenerateColumns: $regenerateColumns")
        console.log("canvas.columns: ${canvas.columns}")
        console.log("canvas.layoutMap: ${canvas.layoutMap}")
        val rootBoundary = rootBoundary()
        val rootColumnId = canvas.root().columnId
        console.log("rootColumnId: $rootColumnId")
        boundaries[rootColumnId] = rootBoundary
        renderLayoutTree(true, rootColumnId, rootBoundary, regenerateColumns)
    }

    // TODO check container dimensions rather than window's
    // note used in export, rendering, surrounds
    fun rootBoundary() = Boundary(0, 0, window.innerWidth, window.innerHeight)

    // resolve a particular column layout, adding dom elements to it
    private fun FlowContent.renderLayoutTree(root: Boolean, parentIndex: ColumnId, parentBoundary: Boundary, regenerateColumns: List<Int>) {

        val layoutSpec = canvas.layoutMap[parentIndex] ?: throw Exception("Layout $parentIndex not found in ${canvas.layoutMap}")

        val dir = when (layoutSpec.split) {
            HORIZONTAL -> {
                "grid-rows-${layoutSpec.contains.size}"
            }
            VERTICAL -> {
                "grid-cols-${layoutSpec.contains.size}"
            }
        }

        val lt = ThemeManager.current
        div("w-full h-full justify-start $dir grid") {
            id = DomIds.column(layoutSpec.columnId)
            // Grid needs explicit row/column sizing and height constraint. min-width:0 lets this node
            // shrink to its parent's track instead of its content's min-content (the default grid-item
            // `min-width: auto`), which otherwise makes a content-heavy nested column overflow its cell
            // and overlap its neighbour horizontally. Only the width is constrained — heights stay
            // free so a stacked split still sizes its rows to content.
            css {
                if (layoutSpec.split == HORIZONTAL) {
                    gridTemplateColumns = GridTemplateColumns("1fr")
                    gridAutoRows = GridAutoRows("1fr")
                } else {
                    gridTemplateRows = GridTemplateRows("1fr")
                    gridAutoColumns = GridAutoColumns("1fr")
                }
                gap = lt.columnGap
                height = 100.pct
                minWidth = 0.px
                border = Border(lt.borderWidth, BorderStyle.valueOf(lt.borderStyle), lt.layoutBorder)   // themed (DOS double-line)
            }

            layoutSpec.contains.mapIndexed { idx, columnId ->
                val boundary = calculateBoundary(layoutSpec.split, parentBoundary, idx, layoutSpec.contains.size)
                if (canvas.layoutMap.containsKey(columnId)) {
                    // recursive
                    renderLayoutTree(false, columnId, boundary, regenerateColumns)
                } else {
                    if (columnId in regenerateColumns) {
                        console.log("Rendering column $columnId - in regenerateColumns")
                        val columnContainer = setupColumnContainer(columnId)
                        div(columnClasses(columnContainer.mode)) {
                            id = DomIds.column(columnId)
                            css { columnCss(columnId, columnContainer.mode) }
                            with(columnContainer) {
                                paint()
                            }
                            // Set up event handlers after DOM element is inserted
                            onMount {
                                setupColumnHandlers(columnId, columnContainer)
                            }
                        }
                    } else {
                        console.log("Column $columnId NOT in regenerateColumns: $regenerateColumns")
                    }
                }
                boundaries[columnId] = boundary
            }
        }

    }

    /**
     * The leaf (content) column actually rendered under ([x], [y]), via DOM hit-testing — accurate
     * even when content makes a stacked split's columns unequal heights (unlike the geometric
     * [boundaries], which assume even splits). Returns null over gaps / layout chrome. Used by the
     * context menu so right-clicking anywhere over a column (even an empty one) targets it.
     */
    fun columnAt(x: Int, y: Int): Int? {
        var el = document.elementFromPoint(x.toDouble(), y.toDouble())
        while (el != null) {
            val colId = el.getAttribute("id")?.let { DomIds.columnId(it) }
            if (colId != null && !canvas.layoutMap.containsKey(colId)) return colId   // leaf, not a layout node
            el = el.parentElement
        }
        return null
    }

    fun findColumnSurrounding(x: Int, y: Int, recurse: Boolean = false): Int {
        val candidates = boundaries.filter { entry ->
            val columnId = entry.key
            val boundary = entry.value
            !canvas.layoutMap.containsKey(columnId) && boundary.surrounds(x, y)
        }.toList()

        if (candidates.size > 1) {
            val best = candidates.filter {
                val inset = it.second.inset(x, y)
                inset.first >= x && inset.second >= y
            }
            if (best.size > 1 || best.isEmpty()) {
                val max = best.maxBy { it.second.leftX + it.second.topY }
                return max.first
            }
            return best.first().first
        } else if (candidates.isEmpty()) {
            rootBoundary().let {
                if (it.surrounds(x, y) || recurse) {
                    throw Exception("Couldn't find boundary containing $x $y - recursion $recurse")
                }

                val (newX, newY) = it.wrap(x, y)
                return findColumnSurrounding(newX, newY, true)
            }
        }
        return candidates.first().first
    }

    private fun setupColumnContainer(columnId: ColumnId): ColumnContainer {
        val colCards = cards(columnId)
        val color = color(columnId)
        val mode = columnModes[columnId] ?: Mode.CARD
        val boundary = boundaries[columnId] ?: rootBoundary()
        val columnContainer = ColumnContainer(color, canvas, mode, colCards, boundary, columnId, eventBus)
        columnContainers[columnId] = columnContainer

        return columnContainer
    }

    // Set up event handlers after the column's DOM element is inserted
    private fun setupColumnHandlers(columnId: ColumnId, columnContainer: ColumnContainer) {
        columnContainer.setupDragDrop(tdzDragDrop)
        columnContainer.setupFocusHandler {
            focus(columnId)
        }
        // Double-click a card column to open it in the editor. In editor mode it's a no-op so the
        // double-click keeps its native meaning (selecting a word); Esc / Ctrl+' go back to cards.
        columnContainer.setupDoubleClickHandler {
            if (modeOf(columnId) == Mode.CARD) {
                focus(columnId)
                toggleMode(columnId)
            }
        }
        // Apply per-column overrides first: setFocused reads the accent (data-focus-color) this sets,
        // so the focused column's hover stripe picks up the column color on the very first paint.
        applyColumnStyle(columnId)
        // Apply focus styling if this is the focused column
        if (columnId == focusedColumn) {
            columnContainer.setFocused(true)
        }
        if (Debugger.debug) {
            columnContainer.showDebugBackground()
        }
        // Show/hide the first-run welcome card based on whether the canvas has any cards now. Runs on
        // every column (re)render, so it appears on an empty canvas and clears the instant one is added.
        IntroCard.evaluate(model, canvas)
    }

    /** Paint a column's per-column overrides (wallpaper image + background color) over the theme defaults. */
    private fun applyColumnStyle(columnId: ColumnId) {
        val el = getHtmlElement(DomIds.column(columnId)) ?: return
        val style = model.getColumnStyle(canvas.name, columnId)
        val color = style.color?.takeIf { it.startsWith("#") }
        if (ThemeManager.current.titleInStrip) {
            // Poodle Rocks: the per-column color is an *accent*, not a fill. It recolors this column's
            // card header strips (the colored band atop each card) and its hover stripe (via the
            // data-focus-color attribute, read in setFocused); the white document sheet stays white.
            if (color != null) el.setAttribute("data-focus-color", color) else el.removeAttribute("data-focus-color")
            setColumnHeaderAccent(columnId, color)
        } else {
            // Every other theme: the color fills the column surface (e.g. tints a Metro tile).
            color?.let { el.style.backgroundColor = it }
        }
        style.wallpaper?.let {
            el.style.backgroundImage = "url(\"$it\")"
            el.style.backgroundSize = "cover"
            el.style.backgroundPosition = "center"
        }
    }

    /**
     * Recolor a single column's card header strips to [color] via an injected, id-scoped rule (so it
     * beats the theme's global `.tdz-card-header` rule and survives card re-renders). A null [color]
     * empties the rule, restoring the theme default.
     */
    private fun setColumnHeaderAccent(columnId: Int, color: String?) {
        styleSheet(ColumnContainer.accentStyleId(columnId)) {
            if (color != null) {
                raw("#${DomIds.column(columnId)} .tdz-card-header { background: $color !important; }")
            }
        }
    }

    /** The leaf column's mode-dependent classes — CARD columns scroll, EDITOR columns clip (Ace scrolls itself). */
    private fun columnClasses(mode: Mode) = when (mode) {
        Mode.CARD -> "w-full h-full overflow-y-auto"
        Mode.EDITOR -> "w-full h-full"
    }

    /**
     * A leaf column's themed chrome. min-width:0 lets the column shrink to its grid track rather
     * than its content's min-content (which would overflow into the next column).
     */
    private fun CssBuilder.columnCss(columnId: ColumnId, mode: Mode) {
        val t = ThemeManager.current
        height = 100.pct
        if (mode == Mode.EDITOR) overflow = Overflow.hidden
        minWidth = 0.px
        border = Border(t.borderWidth, BorderStyle.valueOf(t.borderStyle), t.columnBorder)
        backgroundColor = paletteBg(columnId)
        borderRadius = t.columnRadius
        t.columnShadow.forEach { boxShadow += it }
    }

    private fun reRenderColumn(columnId: ColumnId) {
        // Clean up old column
        columnContainers[columnId]?.beforeRemoval()

        val element = getHtmlElement(DomIds.column(columnId)) ?: return
        val columnContainer = setupColumnContainer(columnId)
        // Same construction as renderLayoutTree's leaf, rebuilt in the column's existing slot.
        element.rebuildInPlace {
            div(columnClasses(columnContainer.mode)) {
                id = DomIds.column(columnId)
                css { columnCss(columnId, columnContainer.mode) }
                with(columnContainer) {
                    paint()
                }
                // Set up event handlers after DOM element is inserted
                onMount {
                    setupColumnHandlers(columnId, columnContainer)
                }
            }
        } ?: return
        // The positioning move blurs a focused editor inside the rebuilt column; re-focus.
        columnContainer.focusEditor()
    }

    fun refreshColumns(columnIds: Set<Int>) {
        columnIds.forEach { columnId ->
            reRenderColumn(columnId)
        }
    }

    fun toggleMode(columnId: ColumnId = focusedColumn) {
        val currentMode = columnModes[columnId] ?: Mode.CARD
        val newMode = currentMode.invert()
        columnModes[columnId] = newMode
        saveModeToStorage(columnId, newMode)
        reRenderColumn(columnId)
    }

    /** The current mode of [columnId] (defaults to CARD if not explicitly set). */
    fun modeOf(columnId: ColumnId): Mode = columnModes[columnId] ?: Mode.CARD

    /** The id of the currently focused column — the keyboard target for splits, clear-done, etc. */
    fun focused(): ColumnId = focusedColumn

    /** Give keyboard focus to the focused column's editor, if it is in editor mode. No-op otherwise. */
    fun focusEditor() { columnContainers[focusedColumn]?.focusEditor() }

    fun focus(columnId: ColumnId) {
        if (columnId != focusedColumn && columnContainers.containsKey(columnId)) {
            // Blur old focused column
            columnContainers[focusedColumn]?.setFocused(false)
            // Focus new column
            focusedColumn = columnId
            columnContainers[columnId]?.setFocused(true)
        }
    }

    fun navigateFocus(direction: Direction) {
        val newFocused = when (direction) {
            Direction.RIGHT -> columnRight(focusedColumn)
            Direction.LEFT -> columnLeft(focusedColumn)
            Direction.UP -> columnUp(focusedColumn)
            Direction.DOWN -> columnDown(focusedColumn)
        }
        focus(newFocused)
    }

    private fun columnRight(currColumnId: Int): Int {
        val boundary = boundaries[currColumnId] ?: return currColumnId
        return try {
            findColumnSurrounding(boundary.leftX + boundary.width + 1, boundary.topY)
        } catch (e: Exception) {
            currColumnId
        }
    }

    private fun columnLeft(currColumnId: Int): Int {
        val boundary = boundaries[currColumnId] ?: return currColumnId
        return try {
            findColumnSurrounding(boundary.leftX - 1, boundary.topY)
        } catch (e: Exception) {
            currColumnId
        }
    }

    private fun columnUp(currColumnId: Int): Int {
        val boundary = boundaries[currColumnId] ?: return currColumnId
        return try {
            findColumnSurrounding(boundary.leftX, boundary.topY - 1)
        } catch (e: Exception) {
            currColumnId
        }
    }

    private fun columnDown(currColumnId: Int): Int {
        val boundary = boundaries[currColumnId] ?: return currColumnId
        return try {
            findColumnSurrounding(boundary.leftX, boundary.topY + boundary.height + 1)
        } catch (e: Exception) {
            currColumnId
        }
    }
}
