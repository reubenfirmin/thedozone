# Arbitrary Column Splits Implementation Plan

## Overview
Transition from fixed layout templates to arbitrary, user-defined column splits with pixel-based positioning, zoom/pan canvas interaction, and keyboard navigation.

## Current State

### Data Structure
```kotlin
data class ColumnLayoutSpec(
    val columnId: ColumnId,
    val split: Split,           // VERTICAL | HORIZONTAL
    val contains: List<ColumnId>
)
```

### Storage Format (localStorage)
```
"canvasName|columnId|splitDirection|childColumnIds"
```

Multiple layouts joined with `||`:
```
"canvas|0|VERTICAL|1,2||canvas|2|HORIZONTAL|3,4"
```

**Key insight:** This already supports arbitrary nesting! Child columns can be either content columns or layout columns.

### Current Limitation
- All children in a split have equal sizes (CSS Grid with `1fr` units)
- No support for fixed pixel sizes or arbitrary proportions

## Phase 1: Add Size Specifications

### 1.1 Extend Data Model

```kotlin
data class ColumnLayoutSpec(
    val columnId: ColumnId,
    val split: Split,
    val contains: List<ColumnId>,
    val sizes: List<ColumnSize>? = null  // null = equal splits (backward compatible)
)

sealed class ColumnSize {
    object Fill : ColumnSize()                    // * or auto-fill
    data class Pixels(val value: Int) : ColumnSize()       // 300
    data class Percent(val value: Float) : ColumnSize()    // 40%
    data class Fraction(val value: Float) : ColumnSize()   // 2fr
}
```

### 1.2 Update Storage Format

Add optional 5th field for sizes:
```
"canvasName|columnId|split|children|sizes"
```

**Examples:**
```javascript
// Backward compatible (no sizes = equal)
"canvas|0|VERTICAL|1,2,3"

// Fixed sidebar + fill
"canvas|0|VERTICAL|1,2|250,*"

// Mixed: pixels, fractions, fill
"canvas|0|VERTICAL|1,2,3|200,2fr,*"

// Percentage-based
"canvas|0|HORIZONTAL|1,2|40%,60%"
```

**Parsing:**
```kotlin
fun parseSize(s: String): ColumnSize = when {
    s == "*" -> ColumnSize.Fill
    s.endsWith("%") -> ColumnSize.Percent(s.dropLast(1).toFloat())
    s.endsWith("fr") -> ColumnSize.Fraction(s.dropLast(2).toFloat())
    else -> ColumnSize.Pixels(s.toInt())
}

fun toStorageString(size: ColumnSize): String = when (size) {
    is Fill -> "*"
    is Pixels -> size.value.toString()
    is Percent -> "${size.value}%"
    is Fraction -> "${size.value}fr"
}
```

### 1.3 Update Rendering

Modify CSS Grid template generation:

```kotlin
fun renderLayoutTree(layout: ColumnLayoutSpec) {
    val template = layout.sizes?.joinToString(" ") { size ->
        when (size) {
            is Fill -> "minmax(0, 1fr)"
            is Pixels -> "${size.value}px"
            is Percent -> "${size.value}%"
            is Fraction -> "${size.value}fr"
        }
    } ?: layout.contains.joinToString(" ") { "1fr" }

    if (layout.split == Split.VERTICAL) {
        style.gridTemplateColumns = template
    } else {
        style.gridTemplateRows = template
    }
}
```

### 1.4 Migration Strategy

- Existing data without sizes field continues to work (equal splits)
- No data migration needed
- Add sizes incrementally as users modify layouts

## Phase 2: Zoom & Pan Canvas

### 2.1 Transform State

```kotlin
data class CanvasTransform(
    val zoom: Float = 1.0f,      // Scale: 0.1 to 10.0
    val panX: Float = 0f,        // X offset in pixels
    val panY: Float = 0f         // Y offset in pixels
)

// Optional: persist per-canvas
data class Canvas(
    // ... existing fields
    val viewTransform: CanvasTransform? = null
)
```

### 2.2 DOM Structure

```html
<div class="canvas-container" style="overflow: hidden; width: 100vw; height: 100vh;">
    <div class="canvas-viewport" style="transform: translate(${panX}px, ${panY}px) scale(${zoom})">
        <!-- Existing column layout here -->
    </div>
</div>
```

### 2.3 Event Handlers

**Zoom (wheel):**
```kotlin
onWheel = { event ->
    event.preventDefault()
    val zoomFactor = if (event.deltaY < 0) 1.1f else 0.9f

    // Zoom toward mouse cursor
    val mouseX = event.clientX.toFloat()
    val mouseY = event.clientY.toFloat()

    val newZoom = (zoom * zoomFactor).coerceIn(0.1f, 10f)
    val zoomRatio = newZoom / zoom

    // Adjust pan to zoom at cursor
    panX = mouseX - (mouseX - panX) * zoomRatio
    panY = mouseY - (mouseY - panY) * zoomRatio
    zoom = newZoom
}
```

**Pan (middle-click or space+drag):**
```kotlin
var isDragging = false
var lastPos = Point(0f, 0f)

onMouseDown = { event ->
    if (event.button == 1 || (event.button == 0 && spaceKeyPressed)) {
        isDragging = true
        lastPos = Point(event.clientX.toFloat(), event.clientY.toFloat())
        event.preventDefault()
    }
}

onMouseMove = { event ->
    if (isDragging) {
        val delta = Point(
            event.clientX - lastPos.x,
            event.clientY - lastPos.y
        )
        panX += delta.x
        panY += delta.y
        lastPos = Point(event.clientX.toFloat(), event.clientY.toFloat())
    }
}

onMouseUp = { isDragging = false }
```

### 2.4 Coordinate Mapping

```kotlin
data class Point(val x: Float, val y: Float)
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

fun viewportToCanvas(viewportX: Float, viewportY: Float): Point =
    Point(
        x = (viewportX - panX) / zoom,
        y = (viewportY - panY) / zoom
    )

fun canvasToViewport(canvasX: Float, canvasY: Float): Point =
    Point(
        x = canvasX * zoom + panX,
        y = canvasY * zoom + panY
    )

fun getColumnBounds(columnId: ColumnId): Rect {
    val element = document.getElementById("column-$columnId") ?: error("Column not found")
    val rect = element.getBoundingClientRect()
    return Rect(rect.left, rect.top, rect.right, rect.bottom)
}
```

### 2.5 Utility Functions

```kotlin
// Zoom to fit all columns
fun zoomToFit() {
    val allBounds = allColumns.map { getColumnBounds(it) }
    val minX = allBounds.minOf { it.left }
    val minY = allBounds.minOf { it.top }
    val maxX = allBounds.maxOf { it.right }
    val maxY = allBounds.maxOf { it.bottom }

    val contentWidth = maxX - minX
    val contentHeight = maxY - minY
    val viewportWidth = window.innerWidth
    val viewportHeight = window.innerHeight

    val zoomX = viewportWidth / contentWidth
    val zoomY = viewportHeight / contentHeight
    zoom = min(zoomX, zoomY) * 0.9  // 90% to add padding

    // Center content
    panX = (viewportWidth - contentWidth * zoom) / 2 - minX * zoom
    panY = (viewportHeight - contentHeight * zoom) / 2 - minY * zoom
}

// Zoom to specific column
fun zoomToColumn(columnId: ColumnId) {
    val bounds = getColumnBounds(columnId)
    zoom = 1.0f  // Or calculate to fit column
    panX = window.innerWidth / 2 - bounds.centerX * zoom
    panY = window.innerHeight / 2 - bounds.centerY * zoom
}
```

## Phase 3: Drag & Drop with Transforms

### 3.1 Custom Drag Implementation

Don't use native HTML5 drag-and-drop (doesn't work well with transforms).

```kotlin
data class DragState(
    val cardId: CardId,
    val sourceColumn: ColumnId,
    val offset: Point,  // Cursor offset from card top-left
    var currentPos: Point,  // Canvas coordinates
    var targetColumn: ColumnId?,
    var targetIndex: Int?
)

var dragState: DragState? = null
```

### 3.2 Drag Start

```kotlin
fun onCardMouseDown(event: MouseEvent, cardId: CardId, columnId: ColumnId) {
    if (event.button != 0) return  // Only left-click

    val cardBounds = getCardBounds(cardId)
    val canvasPos = viewportToCanvas(event.clientX.toFloat(), event.clientY.toFloat())

    dragState = DragState(
        cardId = cardId,
        sourceColumn = columnId,
        offset = Point(
            canvasPos.x - cardBounds.left,
            canvasPos.y - cardBounds.top
        ),
        currentPos = canvasPos,
        targetColumn = null,
        targetIndex = null
    )

    createDragPreview(cardId)
    event.preventDefault()
}
```

### 3.3 Drag Move

```kotlin
fun onMouseMove(event: MouseEvent) {
    val drag = dragState ?: return

    // Update position
    val canvasPos = viewportToCanvas(event.clientX.toFloat(), event.clientY.toFloat())
    drag.currentPos = canvasPos

    // Update preview element (stays at cursor in viewport space)
    dragPreview.style.apply {
        left = "${event.clientX}px"
        top = "${event.clientY}px"
        transform = "translate(-50%, -50%) scale($zoom)"
    }

    // Find drop target
    val targetColumn = findColumnAtPosition(canvasPos.x, canvasPos.y)
    val targetIndex = if (targetColumn != null) {
        findInsertionIndex(targetColumn, canvasPos.y)
    } else null

    drag.targetColumn = targetColumn
    drag.targetIndex = targetIndex

    // Update visual feedback (highlight drop zone)
    updateDropIndicator(targetColumn, targetIndex)
}
```

### 3.4 Drop

```kotlin
fun onMouseUp(event: MouseEvent) {
    val drag = dragState ?: return

    if (drag.targetColumn != null && drag.targetIndex != null) {
        // Perform the move
        moveCard(
            cardId = drag.cardId,
            fromColumn = drag.sourceColumn,
            toColumn = drag.targetColumn!!,
            toIndex = drag.targetIndex!!
        )
    }

    // Cleanup
    removeDragPreview()
    clearDropIndicator()
    dragState = null
}
```

### 3.5 Hit Testing

```kotlin
fun findColumnAtPosition(canvasX: Float, canvasY: Float): ColumnId? {
    return allContentColumns.find { columnId ->
        val bounds = getColumnBounds(columnId)
        canvasX >= bounds.left && canvasX <= bounds.right &&
        canvasY >= bounds.top && canvasY <= bounds.bottom
    }
}

fun findInsertionIndex(columnId: ColumnId, canvasY: Float): Int {
    val cards = getCardsInColumn(columnId)

    for ((index, card) in cards.withIndex()) {
        val bounds = getCardBounds(card.id)
        if (canvasY < bounds.centerY) {
            return index
        }
    }

    return cards.size  // Append to end
}
```

## Phase 4: Keyboard Navigation

### 4.1 Spatial Navigation Algorithm

```kotlin
enum class Direction { UP, DOWN, LEFT, RIGHT }

fun findNextColumn(fromColumn: ColumnId, direction: Direction): ColumnId? {
    val fromBounds = getColumnBounds(fromColumn)
    val candidates = allContentColumns.filter { it != fromColumn }

    return candidates
        .map { col -> col to scoreDirection(fromBounds, getColumnBounds(col), direction) }
        .filter { (_, score) -> score > 0 }  // Must be in correct direction
        .maxByOrNull { (_, score) -> score }
        ?.first
}

fun scoreDirection(from: Rect, to: Rect, direction: Direction): Float {
    return when (direction) {
        Direction.RIGHT -> {
            if (to.left < from.right) return -1f  // Not to the right
            val verticalOverlap = overlapAmount(
                from.top to from.bottom,
                to.top to to.bottom
            )
            val distance = to.left - from.right
            verticalOverlap / (distance + 1)  // Prefer close + aligned
        }
        Direction.LEFT -> {
            if (to.right > from.left) return -1f
            val verticalOverlap = overlapAmount(
                from.top to from.bottom,
                to.top to to.bottom
            )
            val distance = from.left - to.right
            verticalOverlap / (distance + 1)
        }
        Direction.DOWN -> {
            if (to.top < from.bottom) return -1f
            val horizontalOverlap = overlapAmount(
                from.left to from.right,
                to.left to to.right
            )
            val distance = to.top - from.bottom
            horizontalOverlap / (distance + 1)
        }
        Direction.UP -> {
            if (to.bottom > from.top) return -1f
            val horizontalOverlap = overlapAmount(
                from.left to from.right,
                to.left to to.right
            )
            val distance = from.top - to.bottom
            horizontalOverlap / (distance + 1)
        }
    }
}

fun overlapAmount(range1: Pair<Float, Float>, range2: Pair<Float, Float>): Float {
    val (start1, end1) = range1
    val (start2, end2) = range2
    val overlapStart = max(start1, start2)
    val overlapEnd = min(end1, end2)
    return max(0f, overlapEnd - overlapStart)
}
```

### 4.2 Keyboard Shortcuts

```kotlin
fun onKeyDown(event: KeyboardEvent) {
    when {
        // Within-column navigation
        event.key == "ArrowUp" && !event.ctrlKey -> navigateCard(Direction.UP)
        event.key == "ArrowDown" && !event.ctrlKey -> navigateCard(Direction.DOWN)
        event.key == "Enter" -> editFocusedCard()
        event.key == "Escape" -> exitEditMode()

        // Between-column navigation (Ctrl+Arrow)
        event.key == "ArrowRight" && event.ctrlKey -> {
            findNextColumn(currentColumn, Direction.RIGHT)?.let { focusColumn(it) }
        }
        event.key == "ArrowLeft" && event.ctrlKey -> {
            findNextColumn(currentColumn, Direction.LEFT)?.let { focusColumn(it) }
        }
        event.key == "ArrowUp" && event.ctrlKey -> {
            findNextColumn(currentColumn, Direction.UP)?.let { focusColumn(it) }
        }
        event.key == "ArrowDown" && event.ctrlKey -> {
            findNextColumn(currentColumn, Direction.DOWN)?.let { focusColumn(it) }
        }

        // Direct column jump (Ctrl+1-9)
        event.key in "123456789" && event.ctrlKey -> {
            val index = event.key.toInt() - 1
            allContentColumns.getOrNull(index)?.let { focusColumn(it) }
        }

        // Canvas navigation (Ctrl+Shift+Arrow)
        event.key == "ArrowRight" && event.ctrlKey && event.shiftKey -> {
            panX -= 100 / zoom  // Pan right
        }
        event.key == "ArrowLeft" && event.ctrlKey && event.shiftKey -> {
            panX += 100 / zoom  // Pan left
        }

        // Zoom (Ctrl +/-)
        event.key == "+" && event.ctrlKey -> zoom *= 1.1f
        event.key == "-" && event.ctrlKey -> zoom *= 0.9f

        // Utility
        event.key == "f" && event.ctrlKey && event.shiftKey -> zoomToFit()
        event.key == "Home" && event.ctrlKey -> focusColumn(allContentColumns.first())
        event.key == "End" && event.ctrlKey -> focusColumn(allContentColumns.last())
    }
}
```

### 4.3 Auto-Pan on Navigation

```kotlin
fun focusColumn(columnId: ColumnId, animate: Boolean = true) {
    val columnBounds = getColumnBounds(columnId)
    val viewportBounds = Rect(
        -panX / zoom,
        -panY / zoom,
        (window.innerWidth - panX) / zoom,
        (window.innerHeight - panY) / zoom
    )

    // Check if column is fully visible
    val isVisible =
        columnBounds.left >= viewportBounds.left &&
        columnBounds.right <= viewportBounds.right &&
        columnBounds.top >= viewportBounds.top &&
        columnBounds.bottom <= viewportBounds.bottom

    if (!isVisible) {
        val targetPanX = window.innerWidth / 2 - columnBounds.centerX * zoom
        val targetPanY = window.innerHeight / 2 - columnBounds.centerY * zoom

        if (animate) {
            animatePan(targetPanX, targetPanY, duration = 300)
        } else {
            panX = targetPanX
            panY = targetPanY
        }
    }

    currentFocusedColumn = columnId
    highlightColumn(columnId)
}

fun animatePan(targetX: Float, targetY: Float, duration: Int) {
    val startX = panX
    val startY = panY
    val startTime = Date.now()

    fun step() {
        val elapsed = Date.now() - startTime
        val progress = min(1.0, elapsed / duration).toFloat()
        val eased = easeInOutCubic(progress)

        panX = startX + (targetX - startX) * eased
        panY = startY + (targetY - startY) * eased

        if (progress < 1.0) {
            window.requestAnimationFrame { step() }
        }
    }

    window.requestAnimationFrame { step() }
}

fun easeInOutCubic(t: Float): Float =
    if (t < 0.5f) 4 * t * t * t
    else 1 - (-2 * t + 2).pow(3) / 2
```

## Implementation Order

1. **Phase 1.1-1.2**: Extend data model and storage format (~2-3 hours)
2. **Phase 1.3-1.4**: Update rendering logic (~1-2 hours)
3. **Phase 2**: Implement zoom & pan (~3-4 hours)
4. **Phase 3**: Drag & drop with transforms (~4-5 hours)
5. **Phase 4**: Keyboard navigation (~3-4 hours)

**Total estimate**: 13-18 hours of focused development

## Testing Strategy

### Unit Tests
- Size string parsing/serialization
- Coordinate mapping (viewport ↔ canvas)
- Spatial navigation scoring algorithm

### Integration Tests
- Save/load canvas with sizes
- Backward compatibility with existing canvases
- Zoom/pan state persistence

### Manual Testing Scenarios
1. Create column with fixed sidebar (250px) + fill
2. Zoom in/out, verify text rendering
3. Drag card between columns at various zoom levels
4. Navigate columns with keyboard at different zoom levels
5. Test with very large canvas (5000px+)

## Edge Cases & Considerations

### Performance
- CSS transforms are GPU-accelerated (very efficient)
- No virtualization needed unless >1000 cards
- Consider debouncing zoom/pan state persistence

### UX Polish
- Smooth animations (300ms easing for auto-pan)
- Visual feedback for drag targets
- Keyboard shortcut hints (tooltip/modal)
- Preview hover state for Ctrl+Arrow navigation

### Accessibility
- Ensure focus visible at all zoom levels
- Screen reader announcements for column navigation
- High-contrast mode support for drag indicators

### Mobile Considerations (Future)
- Touch gestures: pinch-to-zoom, two-finger pan
- Long-press for drag & drop
- Virtual keyboard handling

## Open Questions

1. **Default canvas size**: Start with what dimensions? (1920x1080? Infinite?)
2. **Min/max zoom levels**: 0.1x to 10x sufficient?
3. **Pan constraints**: Prevent panning beyond content bounds?
4. **Minimap**: Worth adding for large canvases?
5. **Column handles**: Add resize handles for adjusting split sizes?
6. **Snap-to-grid**: Optional snapping for column alignment?
