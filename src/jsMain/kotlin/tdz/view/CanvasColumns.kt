package tdz.view

import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.RefreshColumns
import tdz.controller.events.TaskToggleDone
import tdz.model.Card.Companion.taskItems
import web.events.EventType
import web.events.addEventListener
import web.dom.Element
import js.string.asString
import tdz.model.Canvas
import tdz.model.Card
import tdz.model.CardRef
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
import zoned.framework.routing.Zone
import zoned.framework.interop.important
import zoned.framework.interop.styleSheet


/**
 * One canvas render's layout-and-focus controller: builds the column grid from the canvas's layout
 * tree, owns per-column containers/modes/geometry ([Boundary]s for keyboard navigation), re-renders
 * individual columns in place ([refreshColumns] via Zone.rebuild), applies per-column styles, and
 * wires drag-drop. Created fresh by [CanvasView] on every swap; [saveEditors] runs before teardown.
 */
class CanvasColumns(val canvas: Canvas,
                    val eventBus: EventBus<Event>,
                    val model: Model,
                    /** Viewport-width seam (overridable in tests): true => render only the first column. */
                    val isMobile: () -> Boolean = { window.innerWidth <= MOBILE_BREAKPOINT },
                    val cards: (columnId: ColumnId) -> List<Card>) {

    companion object {
        /** At/below this viewport width the board collapses to its first column only (see [render]). */
        const val MOBILE_BREAKPOINT = 768
    }

    private val boundaries = mutableMapOf<ColumnId, Boundary>()
    private val columnModes = mutableMapOf<ColumnId, Mode>()
    private val columnContainers = mutableMapOf<ColumnId, ColumnContainer>()
    private var focusedColumn: ColumnId = canvas.columns.firstOrNull { !canvas.layoutMap.containsKey(it) } ?: canvas.columns.firstOrNull() ?: ColumnId(0)

    init {
        // Load persisted modes from localStorage
        canvas.columns.forEach { columnId ->
            val persistedMode = loadModeFromStorage(columnId)
            if (persistedMode != null) {
                columnModes[columnId] = persistedMode
            }
        }
    }

    /** Live Ace editors, kept across mode toggles (init is ~100ms; re-adopting the parked DOM is
     *  ~free). Real teardown happens in [saveEditors] when this canvas render is disposed.
     *  (internal for tests) */
    internal val editorCache = mutableMapOf<ColumnId, EditorColumn>()

    /**
     * Save any open editor buffers and tear down their Ace instances. [CanvasView] calls this
     * before each swap — without it, unsaved edits would be silently discarded with the old DOM.
     */
    fun saveEditors() {
        columnContainers.values.forEach { it.beforeRemoval() }
        editorCache.values.forEach { it.destroy() }
        editorCache.clear()
    }

    private fun loadModeFromStorage(columnId: ColumnId): Mode? =
        model.getColumnMode(canvas.name, columnId)

    private fun saveModeToStorage(columnId: ColumnId, mode: Mode) {
        model.setColumnMode(canvas.name, columnId, mode)
    }

    /**
     * The themed background fill for a column. When the theme supplies a [columnPalette] (Metro's
     * live-tile grid) the fill cycles through it by the column's ordinal among leaf (content) columns,
     * so the order is stable and predictable; otherwise every column uses the single `panelBg`. A
     * per-column color override is painted later (onMount) and still wins over this default.
     */
    private fun paletteBg(columnId: ColumnId): Color {
        val t = ThemeManager.current
        if (t.columnPalette.isEmpty()) return t.panelBg
        val leaves = canvas.columns.filter { !canvas.layoutMap.containsKey(it) }
        val idx = leaves.indexOf(columnId).coerceAtLeast(0)
        return t.columnPalette[idx % t.columnPalette.size]
    }

    /** Render the layout tree, (re)building the LEAF columns listed in [regenerateColumns]. */
    fun FlowContent.render(regenerateColumns: List<ColumnId>) {
        // Card columns scroll (wheel/touchpad) but never SHOW a scrollbar. Chrome/Firefox honor
        // the inline scrollbar-width:none (columnCss); WebKit needs this pseudo-element rule,
        // which inline styles cannot express. Idempotent: keyed stylesheet, replaced per render.
        styleSheet("tdz-column-scrollbar") {
            rule("[id^=\"column-\"]::-webkit-scrollbar") { display = Display.none }
        }
        // Mobile: show ONLY the first leaf column, full viewport. The layout tree and every other
        // column are skipped entirely (not in the DOM, no editors built), so they're unreachable.
        if (isMobile()) {
            renderSingleColumn()
            return
        }
        val rootBoundary = rootBoundary()
        val rootColumnId = canvas.root().columnId
        boundaries[rootColumnId] = rootBoundary
        renderLayoutTree(true, rootColumnId, rootBoundary, regenerateColumns, emptySet())
    }

    fun rootBoundary() = Boundary(0, 0, window.innerWidth, window.innerHeight)

    /**
     * The first leaf (content) column in layout order — the top-left-most, found by descending the
     * first child of each layout. This is the one column shown on mobile. Falls back to the first
     * declared content column if the tree is degenerate/cyclic.
     */
    fun firstLeafColumn(): ColumnId {
        var id = canvas.root().columnId
        val guard = mutableSetOf<ColumnId>()
        while (canvas.layoutMap.containsKey(id) && guard.add(id)) {
            id = canvas.layoutMap[id]?.contains?.firstOrNull() ?: break
        }
        return if (canvas.layoutMap.containsKey(id)) canvas.firstNonLayout() else id
    }

    /** Render just the first leaf column, filling the viewport (mobile single-column mode). */
    private fun FlowContent.renderSingleColumn() {
        val leaf = firstLeafColumn()
        boundaries[leaf] = rootBoundary()
        focusedColumn = leaf
        renderLeafColumn(leaf)
    }

    /** Render one leaf (content) column: its themed container, painted cards, and mounted handlers. */
    private fun FlowContent.renderLeafColumn(columnId: ColumnId) {
        val columnContainer = setupColumnContainer(columnId)
        div {
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
    }

    // resolve a particular column layout, adding dom elements to it
    private fun FlowContent.renderLayoutTree(root: Boolean, parentIndex: ColumnId, parentBoundary: Boundary, regenerateColumns: List<ColumnId>, ancestors: Set<ColumnId>) {

        // Cycle guard: a corrupt manifest can reference a layout that's already an ancestor (0→1→0…).
        // Without this the recursion never terminates and stack-overflows, aborting the whole board
        // render (the board goes blank). Break the cycle, log, and render the rest.
        if (parentIndex in ancestors) {
            console.error("[layout] cycle at column $parentIndex in canvas '${canvas.name.normalized()}' — " +
                "skipping (manifest layout tree is corrupt: ${canvas.layouts}).")
            return
        }
        val nextAncestors = ancestors + parentIndex

        val layoutSpec = canvas.layoutMap[parentIndex] ?: throw Exception("Layout $parentIndex not found in ${canvas.layoutMap}")

        val lt = ThemeManager.current
        val tracks = "repeat(${layoutSpec.contains.size}, minmax(0, 1fr))"
        div {
            id = DomIds.column(layoutSpec.columnId)
            // Grid needs explicit row/column sizing and height constraint. minmax(0, 1fr) lets each
            // cell shrink below its content's min-content (the default grid-item `min-width: auto`),
            // which otherwise makes a content-heavy nested column overflow its cell and overlap its
            // neighbour horizontally.
            css {
                display = Display.grid
                width = 100.pct
                height = 100.pct
                justifyContent = JustifyContent.flexStart
                if (layoutSpec.split == HORIZONTAL) {
                    gridTemplateRows = GridTemplateRows(tracks)      // stacked
                    gridTemplateColumns = GridTemplateColumns("1fr")
                } else {
                    gridTemplateColumns = GridTemplateColumns(tracks)   // side by side
                    gridTemplateRows = GridTemplateRows("1fr")
                }
                gap = lt.columnGap
                minWidth = 0.px
                border = Border(lt.borderWidth, BorderStyle.valueOf(lt.borderStyle), lt.layoutBorder)   // themed (DOS double-line)
            }

            layoutSpec.contains.mapIndexed { idx, columnId ->
                val boundary = calculateBoundary(layoutSpec.split, parentBoundary, idx, layoutSpec.contains.size)
                if (canvas.layoutMap.containsKey(columnId)) {
                    // recursive (cycle-guarded via ancestors)
                    renderLayoutTree(false, columnId, boundary, regenerateColumns, nextAncestors)
                } else {
                    if (columnId in regenerateColumns) {
                        renderLeafColumn(columnId)
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
    fun columnAt(x: Int, y: Int): ColumnId? {
        var el = document.elementFromPoint(x.toDouble(), y.toDouble())
        while (el != null) {
            val colId = el.getAttribute("id")?.let { DomIds.columnId(it) }
            if (colId != null && !canvas.layoutMap.containsKey(colId)) return colId   // leaf, not a layout node
            el = el.parentElement
        }
        return null
    }

    fun findColumnSurrounding(x: Int, y: Int, recurse: Boolean = false): ColumnId {
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
                // A probe on a shared edge can disqualify EVERY candidate (each loses on x or y);
                // fall back to the candidates rather than maxBy-ing an empty list.
                val max = best.ifEmpty { candidates }.maxBy { it.second.leftX + it.second.topY }
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
        val mode = columnModes[columnId] ?: Mode.CARD
        val boundary = boundaries[columnId] ?: rootBoundary()
        val columnContainer = ColumnContainer(canvas, mode, colCards, boundary, columnId, eventBus) { id ->
            editorCache.getOrPut(id) { EditorColumn(canvas, id, eventBus) }
        }
        columnContainers[columnId] = columnContainer

        return columnContainer
    }

    // Set up event handlers after the column's DOM element is inserted
    /**
     * ONE delegated click listener per column toggles task checkboxes — task rows carry no
     * per-row closures. The clicked row resolves to its model segment positionally: its ordinal
     * among the enclosing card's task-row elements equals the ordinal in [taskItems] (both are
     * render order). The listener dies with the column element on rebuild, so no detach needed.
     * Inner links/buttons keep working: their own handlers stopPropagation before this sees them.
     */
    private fun wireTaskToggleDelegate(columnId: ColumnId) {
        val columnEl = getHtmlElement(DomIds.column(columnId)) ?: return
        columnEl.addEventListener(EventType("click"), { event ->
            val target = event.target as? Element ?: return@addEventListener
            val row = target.closest(".${CssClasses.TASK}") ?: return@addEventListener
            val cardEl = row.closest(DomIds.CARD_SELECTOR) ?: return@addEventListener
            val ref = DomIds.cardId(cardEl.id.asString()) ?: return@addEventListener
            val rows = cardEl.querySelectorAll(".${CssClasses.TASK}")
            val ordinal = (0 until rows.length).indexOfFirst { rows[it] === row }
            val card = model.getCardByIndex(canvas.name, ref)
            if (card.taskItems().getOrNull(ordinal) == null) return@addEventListener
            eventBus.fire(TaskToggleDone(canvas.name, card, ordinal))
            event.stopPropagation()
            event.preventDefault()
        })
    }

    private fun setupColumnHandlers(columnId: ColumnId, columnContainer: ColumnContainer) {
        wireTaskToggleDelegate(columnId)
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
    private fun setColumnHeaderAccent(columnId: ColumnId, color: String?) {
        styleSheet(ColumnContainer.accentStyleId(columnId)) {
            if (color != null) {
                // !important: must beat the theme's global .tdz-card-header rule.
                rule("#${DomIds.column(columnId)} .${CssClasses.CARD_HEADER}") { important("background", color) }
            }
        }
    }

    /**
     * A leaf column's themed chrome and mode behavior: CARD columns scroll by wheel/touchpad but
     * never SHOW a scrollbar (scrollbar-width here + the ::-webkit-scrollbar rule in [render] —
     * the visual spec has always been "no overflow chrome"); EDITOR columns clip (Ace scrolls
     * itself). min-width:0 lets the column shrink to its grid track rather than its content's
     * min-content (which would overflow into the next column).
     */
    private fun CssBuilder.columnCss(columnId: ColumnId, mode: Mode) {
        val t = ThemeManager.current
        width = 100.pct
        height = 100.pct
        when (mode) {
            Mode.CARD -> {
                overflowY = Overflow.auto
                put("scrollbar-width", "none")
            }
            Mode.EDITOR -> overflow = Overflow.hidden
        }
        minWidth = 0.px
        border = Border(t.borderWidth, BorderStyle.valueOf(t.borderStyle), t.columnBorder)
        backgroundColor = paletteBg(columnId)
        borderRadius = t.columnRadius
        t.columnShadow.forEach { boxShadow += it }
    }

    private fun reRenderColumn(columnId: ColumnId) {
        // Clean up old column
        columnContainers[columnId]?.beforeRemoval()

        val columnContainer = setupColumnContainer(columnId)
        // Each column is a zone. Its mode classes/css live ON the element, so a re-render REBUILDS
        // the zone element in its slot (Zone.swap would keep stale mode styling on the wrapper).
        Zone(DomIds.column(columnId)).rebuild {
            div {
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

    fun refreshColumns(columnIds: Set<ColumnId>) {
        columnIds.forEach { columnId ->
            reRenderColumn(columnId)
        }
    }

    /**
     * Rebuild a single card element in place from the model — the column around it (sibling cards,
     * scroll position, Sortable wiring) is untouched. Falls back to a full column re-render when
     * the ref no longer resolves to an element + card (a stale ref after a structural change).
     */
    fun refreshCard(ref: CardRef) {
        val el = getHtmlElement(DomIds.card(ref))
        val card = cards(ref.columnId).getOrNull(ref.index)
        if (el == null || card == null) {
            refreshColumns(setOf(ref.columnId))
            return
        }
        el.rebuildInPlace { with(CardRenderer) { renderCard(canvas.name, card, eventBus) } }
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

    // Directional probes aim at the MIDPOINT of the facing edge (not its corner): in a mixed
    // layout a corner probe can land beside a neighbour that only overlaps this column's middle.
    private fun columnRight(currColumnId: ColumnId): ColumnId {
        val boundary = boundaries[currColumnId] ?: return currColumnId
        return try {
            findColumnSurrounding(boundary.leftX + boundary.width + 1, boundary.topY + boundary.height / 2)
        } catch (e: Exception) {
            currColumnId
        }
    }

    private fun columnLeft(currColumnId: ColumnId): ColumnId {
        val boundary = boundaries[currColumnId] ?: return currColumnId
        return try {
            findColumnSurrounding(boundary.leftX - 1, boundary.topY + boundary.height / 2)
        } catch (e: Exception) {
            currColumnId
        }
    }

    private fun columnUp(currColumnId: ColumnId): ColumnId {
        val boundary = boundaries[currColumnId] ?: return currColumnId
        return try {
            findColumnSurrounding(boundary.leftX + boundary.width / 2, boundary.topY - 1)
        } catch (e: Exception) {
            currColumnId
        }
    }

    private fun columnDown(currColumnId: ColumnId): ColumnId {
        val boundary = boundaries[currColumnId] ?: return currColumnId
        return try {
            findColumnSurrounding(boundary.leftX + boundary.width / 2, boundary.topY + boundary.height + 1)
        } catch (e: Exception) {
            currColumnId
        }
    }
}
